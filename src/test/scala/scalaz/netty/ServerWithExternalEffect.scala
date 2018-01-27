package scalaz.netty

import java.net.InetSocketAddress
import java.util.concurrent.Executors._

import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.concurrent.forkjoin.ThreadLocalRandom
import scalaz.concurrent.{Task, Strategy}
import scalaz.stream.{process1, Process}

class ServerWithExternalEffect extends Specification with ScalazNettyConfig {

  override val address = new InetSocketAddress("localhost", 9093)

  "Batching client and external effects server" should {
    "run" in {
      val n = 150
      val bs = n / 15
      val io = newFixedThreadPool(2, namedThreadFactory("external-effect"))
      val E = newFixedThreadPool(4, namedThreadFactory("netty-worker"))
      val S = Strategy.Executor(E)
      val cfg = ServerConfig(true, 4, 50, true)

      def server = P.repeatEval(Task now { bts: ByteVector ⇒
        Task {
          logger.info("external call")
          Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(100, 200))

          if (bts.decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r) == "stop")
            throw new Exception("Stop command received") //not a best way, but ...

          P.emit(greeting ++ bts)
        }(io)
      })

      val EchoGreetingServer = scalaz.stream.merge.mergeN(1)(Netty.server(address, cfg)(E).map { v ⇒
        val addr = v._1
        val exchange = v._3
        for {
          _ ← Process.eval(Task.delay(logger.info(s"Accepted connection from $addr")))
          out ← exchange.read through server
          _ ← out to exchange.write
        } yield ()
      })(S)

      EchoGreetingServer.runLog.runAsync(_ ⇒ ())

      def batchClient(message: String) = Netty connect address flatMap { exchange ⇒
        val source: Process[Task, ByteVector] = P.emitAll(Seq.fill(n)(ByteVector(message.getBytes(enc))))
        (for {
          batch ← source.chunk(bs)
          _ ← P.emitAll(batch) |> process1.lift { b ⇒ logger.info(s"client write"); b } to exchange.write
          data ← exchange.read.take(1).map(_.decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r))
          _ ← Process.eval(Task.delay(logger.info(s"client receive: $data")))
        } yield (data)) ++ (P.emit(ByteVector("stop".getBytes(enc))) to exchange.write)
      }

      val r = batchClient("Bob").runLog.run
      r.size must be equalTo n + 1
    }
  }
}