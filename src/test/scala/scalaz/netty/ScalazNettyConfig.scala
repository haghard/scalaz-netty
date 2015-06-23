package scalaz.netty

import scalaz.stream.Process
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ThreadFactory, Executors }

import org.apache.log4j.Logger
import scodec.Codec
import scodec.bits.ByteVector

import scalaz.concurrent.Task
import scalaz.stream._

trait ScalazNettyConfig {
  val enc = java.nio.charset.Charset.forName("UTF-8")
  val greeting = ByteVector("Hello ".getBytes(enc))

  implicit val scheduler = {
    Executors.newScheduledThreadPool(2, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scheduled-task-thread")
        t
      }
    })
  }

  val P = Process
  val logger = Logger.getLogger("netty-server")

  def LoggerS: Sink[Task, String] = sink.lift[Task, String] { line ⇒
    Task.delay(logger.info(s"Client receive: $line"))
  }

  val codec: Codec[String] = scodec.codecs.utf8

  val codecInt: Codec[Int] = scodec.codecs.int32

  val encUtf = scodec.stream.encode.many(codec)
  val decUtf = scodec.stream.decode.many(codec)

  val encInt = scodec.stream.encode.many(codecInt)
  val decInt = scodec.stream.decode.many(codecInt)

  def transcodeUtf(ex: Exchange[ByteVector, ByteVector]) = {
    val Exchange(src, sink) = ex
    val src2 = src.map(_.toBitVector).flatMap(b ⇒ decUtf.decode(b))
    Exchange(src2, sink)
  }

  def transcodeInt(ex: Exchange[ByteVector, ByteVector]) = {
    val Exchange(src, sink) = ex
    val src2 = src.map(_.toBitVector).flatMap(b ⇒ decInt.decode(b))
    Exchange(src2, sink)
  }

  def address: InetSocketAddress

  val PoisonPill = "Poison"

  def requestSrc(mes: String): Process[Task, ByteVector] = {
    def go(mes: String): Process[Task, String] =
      P.await(Task.delay(mes))(m ⇒ P.emit(s"$mes-${System.currentTimeMillis()}") ++ go(mes))

    (go(mes) |> encUtf.encoder) map (_.toByteVector)
  }

  def namedThreadFactory(name: String) = new ThreadFactory {
    val num = new AtomicInteger(1)
    def newThread(runnable: Runnable) = new Thread(runnable, s"$name - ${num.incrementAndGet}")
  }

  def naturals: Process[Task, Long] = {
    def go(i: Long): Process[Task, Long] =
      Process.await(Task.delay(i))(i ⇒ Process.emit(i) ++ go(i + 1l))
    go(1l)
  }
}
