package scalaz.netty

import org.specs2.mutable.Specification

import java.net.InetSocketAddress
import java.util.concurrent.Executors._

import scodec.bits.ByteVector

import scala.collection.mutable.Buffer
import scalaz._
import scalaz.concurrent.{ Strategy, Task }
import scalaz.stream.Process._
import scalaz.stream.{ io, merge, _ }

class ScalazNettyRequestResponseSpec extends Specification with ScalazNettyConfig {

  override val address = new InetSocketAddress("localhost", 9091)

  "Request-response server with 2 clients" should {
    "run echo" in {
      val n = 5
      val S = newFixedThreadPool(4, namedThreadFactory("netty-worker2"))
      val C = newFixedThreadPool(2, namedThreadFactory("netty-client"))

      def serverHandler(cmd: String) = {
        logger.info(s"[server] receive $cmd")
        if (cmd == PoisonPill) throw new Exception("Stop command received")
        greeting ++ ByteVector(cmd.getBytes(enc))
      }

      val EchoGreetingServer = merge.mergeN(2)(Netty.server(address)(S) map { v ⇒
        for {
          _ ← Process.eval(Task.delay(logger.info(s"Connection had accepted from ${v._1}")))
          Exchange(src, sink) = transcodeUtf(v._3)
          _ ← src map serverHandler to sink
        } yield ()
      })(Strategy.Executor(S))

      EchoGreetingServer.run.runAsync(_ ⇒ ())

      def client(mes: String, buf: Buffer[String]) = {
        val clientStream = requestSrc(mes).take(n) ++ (P.emit(PoisonPill) |> encUtf.encoder).map(_.toByteVector)

        for {
          exchange ← Netty.connect(address)(C)
          Exchange(src, sink) = transcodeUtf(exchange)

          out = clientStream |> process1.lift { b ⇒ logger.info(s"send $mes"); b } to sink
          in = src observe (LoggerS) to io.fillBuffer(buf)

          /*
           * Request response mode with `zip`.
           * Order significant for  req-resp flow
           * Don't wait last response because it kills server
           */
          _ ← (out zip in.take(n))

        //request non deterministic req/resp flow  with `merge`
        //_ ← (out merge in)(Strategy.Executor(C))
        } yield ()
      }

      val ND = Nondeterminism[Task]
      val bufBob = Buffer.empty[String]
      val bufAlice = Buffer.empty[String]

      val r = ND.both(client("echo Bob", bufBob).runLog[Task, Any], client("echo Alice", bufAlice).runLog[Task, Any]).run
      bufBob.size must be equalTo n
      bufAlice.size must be equalTo n
    }
  }
}
