package scalaz.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._

object Application extends App {
  //args
  val bossGroup: EventLoopGroup = new NioEventLoopGroup()
  val workerGroup: EventLoopGroup = new NioEventLoopGroup()
  val Port  = 8888
  //sys.addShutdownHook { () => println("ShutdownHook") }
  /*
  val s = new Server2(8888)
  s.start()
  */

  try {
    val b: ServerBootstrap = new ServerBootstrap()
    b.group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
      //.childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
      //.childOption[java.lang.Boolean](ChannelOption.AUTO_READ, false)
      .childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          //ch.pipeline().addLast("codec", new HttpServerCodec())
          //ch.pipeline.addLast("decoder", new HttpRequestDecoder())
          ch.pipeline.addLast("decoder", new HttpRequestDecoder)
          ch.pipeline.addLast("encoder", new HttpResponseEncoder)
          ch.pipeline.addLast("request-handler", new HttpRequestHandler())
          ch.pipeline.addLast("content-handler", new HttpStaticContentHandler())
        }
      })

    val ch: Channel = b.bind(Port).sync().channel
    println(s"http://127.0.0.1:$Port")

    ch.closeFuture().sync()
  } catch {
    case ex: Exception =>  println(ex.getMessage)
  } finally {
    bossGroup.shutdownGracefully
    workerGroup.shutdownGracefully
  }
}