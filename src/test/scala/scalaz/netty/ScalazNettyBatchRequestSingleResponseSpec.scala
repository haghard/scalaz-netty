package scalaz.netty

import java.net.InetSocketAddress
import java.util.concurrent.Executors._
import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.collection.mutable.Buffer
import scala.concurrent.duration.Duration
import scalaz.{\/-, Nondeterminism}
import scalaz.concurrent.{ Strategy, Task }
import scalaz.netty.Server.{ ServerState, TaskVar }
import scalaz.stream.Process._
import scalaz.stream._
import scalaz.stream.process1._

class ScalazNettyBatchRequestSingleResponseSpec extends Specification with ScalazNettyConfig {

  override val address = new InetSocketAddress("localhost", 9095)

  "Request(N) aggregated response server/client" should {
    "run with tee" in {
      val batchSize = 5
      val iterationN = 8
      val clientSize = 3

      val P = Process
      val ES = newFixedThreadPool(2, namedThreadFactory("server-body"))
      val C = newFixedThreadPool(clientSize, namedThreadFactory("client"))

      val bufBob = Buffer.empty[String]
      val bufAlice = Buffer.empty[String]
      val bufJack = Buffer.empty[String]


      def discreteTime(stepMs: Long = 50l): Process[Task, Long] = Process.suspend {
        Process.repeatEval {
          Task.delay { Thread.sleep(stepMs); System.nanoTime }
        }
      }

      def combinedRequest[I](duration: Duration, maxSize: Int = Int.MaxValue): scalaz.stream.Wye[Long, I, Vector[I]] = {
        import scalaz.stream.ReceiveY.{ HaltOne, ReceiveL, ReceiveR }
        val timeWindow = duration.toNanos

        def go(acc: Vector[I], last: Long): Wye[Long, I, Vector[I]] =
          P.awaitBoth[Long, I].flatMap {
            case ReceiveL(current) ⇒
              if (current - last > timeWindow || acc.size >= maxSize) P.emit(acc) ++ go(Vector(), current)
              else go(acc, last)
            case ReceiveR(i) ⇒
              if (acc.size + 1 >= maxSize) P.emit(acc :+ i) ++ go(Vector(), last)
              else go(acc :+ i, last)
            case HaltOne(e) ⇒
              if (!acc.isEmpty) P.emit(acc) ++ P.Halt(e)
              else P.Halt(e)
          }

        go(Vector(), System.nanoTime)
      }

      val disconnected = async.signalOf(0)(Strategy.Executor(newFixedThreadPool(1, namedThreadFactory("signal"))))

      def serverHandler(batch: Vector[ByteVector], state: TaskVar[ServerState], address: InetSocketAddress) = {
        state.modify { c ⇒
          c.copy(tracker = c.tracker + (address -> (c.tracker.getOrElse(address, 0l) + batch.size)))
        }.run

        logger.info(s"Processing batch: ${batch.map(_.decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r)).mkString(", ")}")
        Thread.sleep(500)

        if (batch(0).decodeUtf8.fold(ex ⇒ ex.getMessage, r ⇒ r) == PoisonPill) {
          logger.info(s"Disconnect client")
          disconnected.compareAndSet(_.map(_ + 1)).run
        }
        batch reduce (_ ++ _)
      }

      val cfg = scalaz.netty.ServerConfig(true, clientSize, batchSize, true)
      val S = Strategy.Executor(ES)

      //This is a server that wil be stopped if disconnected == 0
      import scala.concurrent.duration._
      val EchoGreetingServer = merge.mergeN(clientSize)(Netty.server(address, cfg)(ES).map { v ⇒
        for {
          _ ← P.eval(Task.delay(logger.info(s"Start interact with client from ${v._1}")))
          address = v._1
          state = v._2
          Exchange(src, sink) = v._3
          _ ← P.eval(state.modify(c ⇒ c.copy(tracker = c.tracker + (address -> 0l))))
          e = (discreteTime(500l) wye src)(combinedRequest(2 seconds))(S) map { bs ⇒ serverHandler(bs, state, address) } to sink
          //e = src chunk batchSize map { bs ⇒ serverHandler(bs, state, address) } to sink
          _ ← (disconnected.discrete.map(x ⇒ if (x < clientSize) false else true)).wye(e)(wye.interrupt)(S)
            .onComplete(P.eval_(throw new Exception("All clients were disconnected")))
        } yield ()
      })(S)

      def client(target: String, buf: Buffer[String]) = {
        /*val bWye = wye.dynamic(
        (x: (Unit, Long)) ⇒ if (x._2 % batchSize == 0) wye.Request.R else wye.Request.L,
        (y: Unit) ⇒ wye.Request.L
        )*/

        def zipN[I, I2](n: Int): Tee[I, I2, Any] = {
          def go(n: Int, limit: Int): Tee[I, I2, Any] = {
            if (n > 0) awaitL[I] ++ go(n - 1, limit)
            else awaitR[I2] ++ go(limit, limit)
          }
          go(n, n)
        }

        def countDownLeft[I, I2](n: Int): Wye[I, I2, Any] = {
          def go(n: Int, limit: Int): Wye[I, I2, Any] = {
            if (n > 0) wye.receiveL[I, I2, Any] { l: I ⇒ emit[I](l) ++ go(n - 1, limit) }
            else wye.receiveR[I, I2, Any] { r: I2 ⇒ emit[I2](r) ++ go(limit, limit) }
          }
          go(n, n)
        }

        def next[I, I2](l: I, r: I2, n: Int, limit: Int): Tee[I, I2, Any] =
          if (n > 0) nextL(l, n - 1, limit) else nextR(r, limit, limit)

        def nextR[I, I2](r: I2, n: Int, limit: Int): Tee[I, I2, Any] = tee.receiveROr[I, I2, Any](emit(r))(next(_, r, n, limit))
        def nextL[I, I2](l: I, n: Int, limit: Int): Tee[I, I2, Any] = tee.receiveLOr[I, I2, Any](emit(l))(next(_, l, n, limit))

        def initT[I, I2](n: Int, limit: Int): Tee[I, I2, Any] = tee.receiveL[I, I2, Any] { l: I ⇒ emit[I](l); nextL[I, I2](l, n - 1, limit) }

        def init[I, I2](n: Int, limit: Int): Tee[I, I2, Any] = awaitL[I].flatMap { nextL(_, n - 1, n) }

        def zipDeterministic[I, I2](n: Int): Tee[I, I2, Any] = init[I, I2](n, n)

        val n = iterationN * batchSize
        val poison = (P.emitAll(Seq.fill(batchSize)(PoisonPill)) |> encUtf.encoder) map (_.toByteVector)

        for {
          exchange ← Netty.connect(address)(C)
          Exchange(src, sink) = transcodeUtf(exchange)

          out = (requestSrc(target) take n) ++ poison |> lift { b ⇒ logger.info(s"send for $target"); b } to sink
          in = src observe LoggerS to io.fillBuffer(buf)

          //_ ← (out tee in)(zipDeterministic(batchSize))
          //_ ← ((out zip naturals).wye(in)(bWye)(Strategy.Executor(C)))
          _ ← (out wye in)(countDownLeft(batchSize))(Strategy.Executor(C)).take((batchSize + 1) * iterationN + batchSize)
        } yield ()
      }

      //Start server
      EchoGreetingServer.run.runAsync(_ ⇒ ())

      //Start clients
      val r = Nondeterminism[Task].nmap3(client("Bob", bufBob).run,
        client("Alice", bufAlice).run, client("Jack", bufJack).run) { (l: Unit, r: Unit, th: Unit) ⇒ true }
        .attemptRun

      r must be equalTo \/-(true)

      bufBob.size must be equalTo iterationN
      bufAlice.size must be equalTo iterationN
      bufJack.size must be equalTo iterationN
    }
  }
}