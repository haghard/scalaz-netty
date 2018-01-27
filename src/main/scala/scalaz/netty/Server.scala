/*
 * Copyright 2015 RichRelevance
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scalaz
package netty

import java.util.concurrent.atomic.AtomicReference

import org.apache.log4j.Logger

import concurrent._
import scalaz.netty.Netty.{ServerIn, NettyThreadFactory}
import scalaz.netty.Server.{TaskVar, ServerState}
import stream._
import syntax.monad._

import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import _root_.io.netty.bootstrap._
import _root_.io.netty.buffer._
import _root_.io.netty.channel._
import _root_.io.netty.channel.nio._
import _root_.io.netty.channel.socket._
import _root_.io.netty.channel.socket.nio._
import _root_.io.netty.handler.codec._

private[netty] object Server {
  val logger = Logger.getLogger("scalaz-netty-server")

  case class ServerState private[netty](messageNum: Long = 0l, errorNum: Long = 0l, 
                                        tracker: Map[InetSocketAddress, Long] = Map[InetSocketAddress, Long]())
  
  /** An atomically updatable reference, guarded by the `Task` monad. */
  sealed trait TaskVar[A] {
    def read: Task[A]
    def write(value: A): Task[Unit]
    def transact[B](f: A => (A, B)): Task[B]
    def compareAndSet(oldVal: A, newVal: A): Task[Boolean]
    def modify(f: A => A): Task[Unit] = transact(a => (f(a), ()))
  }

  object TaskVar {
    def apply[A](value: => A): TaskVar[A] = new TaskVar[A] {
      private val register = new AtomicReference(value)

      def read = Task(register.get)
      def write(value: A) = Task(register.set(value))
      def compareAndSet(oldVal: A, newVal: A) =
        Task(register.compareAndSet(oldVal, newVal))
      def transact[B](f: A => (A, B)): Task[B] = {
        for {
          a <- read
          (newA, b) = f(a)
          p <- compareAndSet(a, newA)
          r <- if (p) Task.now(b) else transact(f)
        } yield r
      }
    }
  }

  def apply(bind: InetSocketAddress, config: ServerConfig)(implicit pool: ExecutorService): Task[Server] = Task delay {
    val bossThreadPool = new NioEventLoopGroup(1, NettyThreadFactory("boss"))
    val workerThreadPool = new NioEventLoopGroup(config.workerNum, NettyThreadFactory("worker"))

    val server = new Server(bossThreadPool, config.channelQueueMaxSize, TaskVar(ServerState()))
    val bootstrap = new ServerBootstrap

    bootstrap.group(bossThreadPool, workerThreadPool)
      .channel(classOf[NioServerSocketChannel])
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
      .childOption[java.lang.Boolean](ChannelOption.AUTO_READ, false)
      .childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          if (config.codeFrames) {
            ch.pipeline
              .addLast("frame encoding", new LengthFieldPrepender(4))
              .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          }

          //ch.pipeline.addLast("deframer", new server.Deframer)
          ch.pipeline.addLast("incoming handler", new server.Handler(ch))
        }
      })

    val bindF = bootstrap.bind(bind)
    val startMassage = new StringBuilder("\n")
      .append(s"★ ★ ★ ★ ★ ★  Start server on $bind  ★ ★ ★ ★ ★ ★")
      .append("\n")
      .append(s"★ ★ ★ ★ ★ ★  Netty WorkerNum: ${config.workerNum}  ★ ★ ★ ★ ★ ★")
      .append("\n")
      .append(s"★ ★ ★ ★ ★ ★  Buffer size: ${config.channelQueueMaxSize}  ★ ★ ★ ★ ★ ★")

    logger.info(startMassage)

    for {
      _ <- Netty toTask bindF
      _ <- Task delay {
        server.channel = bindF.channel
      }
    } yield server
  } join
}

private[netty] class Server(bossGroup: NioEventLoopGroup, queueSize: Int,
                            state: TaskVar[ServerState])(implicit pool: ExecutorService) { server =>
  import Server._

  private var channel: _root_.io.netty.channel.Channel = _

  // represents incoming connections
  private val queue = async.boundedQueue[ServerIn](queueSize)(Strategy.Executor(pool))

  def listen: Process[Task, ServerIn] = queue.dequeue

  def shutdown(implicit pool: ExecutorService): Task[Unit] = {
    logger.info(s"★ ★ ★ ★ ★ ★ Shutdown server ${state.read.unsafePerformSync} ★ ★ ★ ★ ★ ★")
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
      _ <- Task.delay(bossGroup.shutdownGracefully())
    } yield ()
  }

/*
  sealed trait Frame
  case class Content(bts: BitVector) extends Frame
  case object EOF extends Frame

  private final class Deframer extends ByteToMessageDecoder {

    private var remaining: Option[Int] = None

    override protected def decode(ctx: ChannelHandlerContext, in: ByteBuf,
                                  out: java.util.List[Object]): Unit =  {
      remaining match {
        case None =>
          // we are expecting a frame header which is the number of bytes in the upcoming frame
          if (in.readableBytes >= 4) {
            val rem = in.readInt
            if(rem == 0) out.add(EOF)
            else remaining = Some(rem)
          }
        case Some(rem) =>
          // we are waiting for at least rem more bytes, as that is what
          // is outstanding in the current frame
          if(in.readableBytes() >= rem) {
            val bytes = new Array[Byte](rem)
            in.readBytes(bytes)
            remaining = None
            val bits = BitVector.view(bytes)
            out.add(Content(bits))
          }
      }
    }
  }*/

  final class Handler(channel: SocketChannel)(implicit pool: ExecutorService) extends ChannelInboundHandlerAdapter {
    // data from a single connection
    private val channelQueue = async.boundedQueue[ByteVector](queueSize)(Strategy.Executor(pool))

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      val exchange: Exchange[ByteVector, ByteVector] = Exchange(read, write)
      logger.info(s"New connection from ${channel.remoteAddress}")
      server.queue.enqueueOne((channel.remoteAddress, state, exchange)).run

      //init read from channel since ChannelOption.AUTO_READ == false
      ctx.read()
      super.channelActive(ctx)
    }

    override def channelInactive(ctx: ChannelHandlerContext): Unit = {
      // if the connection is remotely closed, we need to clean things up on our side
      shutdown
      super.channelInactive(ctx)
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
      val buf = msg.asInstanceOf[ByteBuf]
      //val bv = ByteVector(buf.nioBuffer)
      val dst = Array.ofDim[Byte](buf.readableBytes)
      buf.readBytes(dst)
      //zero copy conversion
      val bv = ByteVector.view(dst)
      buf.release

      //Blocking on queue will happen in pool thread, event-loops free to go
      Task.fork(channelQueue.enqueueOne(bv))(pool)
        .unsafePerformAsync { _ => ctx.read() }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
      logger.debug(s"Netty channel exception: ${t.getMessage}")
      shutdown
    }

    private def read: Process[Task, ByteVector] = channelQueue.dequeue

    private def write: Sink[Task, ByteVector] = {
      def writer(bv: ByteVector): Task[Unit] = {
        Task delay {
          val data = bv.toArray
          val buf = channel.alloc().buffer(data.length)
          buf.writeBytes(data)
          channel.eventLoop().execute(new Runnable() {
            override def run: Unit = {
              channel.writeAndFlush(buf)
            }
          })
        }
      }

      Process constant (writer _)
    }

    def shutdown(): Task[Unit] = {
      for {
        _ <- Netty toTask channel.close()
        _ <- channelQueue.close
      } yield ()
    }
  }
}

final case class ServerConfig(keepAlive: Boolean, workerNum: Int, channelQueueMaxSize: Int, codeFrames: Boolean)

object ServerConfig {
  val Default = ServerConfig(true, Runtime.getRuntime.availableProcessors/2, 100, true)
}
