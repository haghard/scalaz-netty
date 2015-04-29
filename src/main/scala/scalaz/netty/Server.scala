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

import org.apache.log4j.Logger

import concurrent._
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

private[netty] class Server(bossGroup: NioEventLoopGroup, limit: Int)(implicit pool: ExecutorService) { server =>
  private val logger = Logger.getLogger("scalaz-netty-server")

  // this isn't ugly or anything...
  private var channel: _root_.io.netty.channel.Channel = _

  // represents incoming connections
  private val queue = async.boundedQueue[(InetSocketAddress, Exchange[ByteVector, ByteVector])](limit)(Strategy.Executor(pool))

  def listen: Process[Task, (InetSocketAddress, Exchange[ByteVector, ByteVector])] =
    queue.dequeue

  def shutdown(implicit pool: ExecutorService): Task[Unit] = {
    logger.debug(".... shutdown server")
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close

      _ <- Task delay {
        bossGroup.shutdownGracefully()
      }
    } yield ()
  }

  private final class Handler(channel: SocketChannel)(implicit pool: ExecutorService) extends ChannelInboundHandlerAdapter {
    // data from a single connection
    private val channelQueue = async.boundedQueue[ByteVector](limit)(Strategy.Executor(pool))

    override def channelActive(ctx: ChannelHandlerContext): Unit = {
      val exchange: Exchange[ByteVector, ByteVector] = Exchange(read, write)
      server.queue.enqueueOne((channel.remoteAddress, exchange)).run

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
      val bv = ByteVector(buf.nioBuffer)       // copy data (alternatives are insanely clunky)
      buf.release()

      Task.fork(channelQueue.enqueueOne(bv))(pool).runAsync { _ => ctx.read() }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
      logger.debug("Error " + t.getMessage)
      shutdown
    }

    // do not call more than once!
    private def read: Process[Task, ByteVector] = channelQueue.dequeue

    private def write: Sink[Task, ByteVector] = {
      def inner(bv: ByteVector): Task[Unit] = {
        Task delay {
          val data = bv.toArray
          val buf = channel.alloc().buffer(data.length)
          buf.writeBytes(data)

          Netty toTask channel.writeAndFlush(buf)
        } join
      }

      // TODO termination
      Process constant (inner _)
    }

    def shutdown: Task[Unit] = {
      for {
        _ <- Netty toTask channel.close()
        _ <- channelQueue.close
      } yield ()
    }
  }
}

private[netty] object Server {
  def apply(bind: InetSocketAddress, config: ServerConfig)(implicit pool: ExecutorService): Task[Server] = Task delay {
    val bossGroup = new NioEventLoopGroup()

    val server = new Server(bossGroup, config.channelQueueMaxSize)
    val bootstrap = new ServerBootstrap

    bootstrap.group(bossGroup, Netty.workerGroup(config.workerNum))
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

          ch.pipeline.addLast("incoming handler", new server.Handler(ch))
        }
      })

    val bindF = bootstrap.bind(bind)

    for {
      _ <- Netty toTask bindF
      _ <- Task delay {
        server.channel = bindF.channel()      // yeah!  I <3 Netty
      }
    } yield server
  } join
}

final case class ServerConfig(keepAlive: Boolean, workerNum: Int, channelQueueMaxSize: Int, codeFrames: Boolean)

object ServerConfig {
  // 1000?  does that even make sense?
  val Default = ServerConfig(true, 4, 1000, true)
}
