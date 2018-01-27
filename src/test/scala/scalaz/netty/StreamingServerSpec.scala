package scalaz.netty

import java.util.Date
import java.net.InetSocketAddress
import java.util.concurrent.Executors._
import java.util.concurrent.TimeUnit

import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration
import scalaz.Nondeterminism
import scalaz.concurrent.{ Strategy, Task }
import scalaz.stream._

class StreamingServerSpec extends Specification with ScalazNettyConfig {
  val n = 15
  override val address = new InetSocketAddress("localhost", 9092)

  val C = newFixedThreadPool(2, namedThreadFactory("client-worker"))
  val S = newFixedThreadPool(5, namedThreadFactory("server-worker"))
  val Topic = Strategy.Executor(S)

  "Streaming with period the same content" should {
    "run" in {
      val topic = async.topic[ByteVector]()(Topic)
      val period = new FiniteDuration(1, TimeUnit.SECONDS)

      (time.awakeEvery(period).zip(P.range(0, n)))
        .map(_ ⇒ topic.publishOne(ByteVector(new Date().toString.getBytes)).unsafePerformSync)
        .runLog.unsafePerformAsync(_ ⇒ ())

      val cleanup = Task.delay {
        logger.info("StreamingTimeServer complete")
        topic.close.unsafePerformSync
      }

      val errorHandler: Cause ⇒ Process[Task, Exchange[ByteVector, ByteVector]] = {
        case cause @ Cause.End ⇒ Process.Halt(Cause.End)
        case cause @ Cause.Kill ⇒ Process.Halt(Cause.End)
        case cause @ Cause.Error(ex) ⇒
          ex match {
            case e: java.nio.channels.ClosedChannelException ⇒
              logger.debug("Client disconnected")
              Process.Halt(Cause.End)
            case other ⇒
              logger.debug(s"Server Exception $other")
              Process.Halt(cause)
          }
      }

      /**
       *
       */
      val clientsInput: Sink[Task, ByteVector] = sink.lift[Task, ByteVector] { bts: ByteVector ⇒
        Task.delay {
          val cmd = bts.decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r)
          logger.info(s"Client say $cmd")
          if (cmd == "stop") throw new Exception("Kind of graceful exit")
          else throw new Exception("Unexpected command from client")
        }
      }

      //Server for at most 2 parallel clients
      scalaz.stream.merge.mergeN(2)(Netty.server(address)(S).map { v ⇒
        val addr = v._1
        val exchange = v._3
        (for {
          _ ← Process.eval(Task.delay(logger.info(s"Start interact with server $addr")))
          in = exchange.read to clientsInput
          out = topic.subscribe to exchange.write
          _ ← (in merge out)(Strategy.Executor(S))
        } yield ()) onHalt errorHandler
      })(Topic).onComplete(P.eval(cleanup)).runLog.unsafePerformAsync(_ ⇒ ())

      def client(id: Long, n: Int) = Netty.connect(address)(C).flatMap { exchange ⇒
        (for {
          data ← exchange.read.take(n).map(_.decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r))
          _ ← Process.eval(Task.delay(logger.info(s"client $id receive: $data")))
        } yield (data))
      }

      val ND = Nondeterminism[Task]

      val m = n - 5
      val (l, r) = ND.both(client(1, n).runLog[Task, Any], client(2, m).runLog[Task, Any]).run

      Netty.connect(address)
        .flatMap { ex ⇒ P.emit(ByteVector("stop".getBytes(enc))) to ex.write }.runLog.run

      l.size must be equalTo n
      r.size must be equalTo m
    }
  }
}