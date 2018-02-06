package scalaz.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.HashMap;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class Server2 {

  private final int port;

  public Server2(int port) {
    this.port = port;
  }

  public void start() throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup();
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new ServerInitializer());
      Channel ch = b.bind(port).sync().channel();
      ch.closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  private class ServerInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    public void initChannel(SocketChannel ch) {
      ChannelPipeline p = ch.pipeline();
      p.addLast("decoder", new HttpRequestDecoder());
      p.addLast("encoder", new HttpResponseEncoder());
      p.addLast("handler", new ServerHandler());
    }
  }

  class ServerHandler extends SimpleChannelInboundHandler<Object> {
    private HttpRequest request;
    private final StringBuilder buf = new StringBuilder();
    private Map<String, UriHandlerBased> handlers = new HashMap<>();

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
      UriHandlerBased handler = null;
      if (msg instanceof HttpRequest) {
        System.out.println("in channelRead HttpRequest");

        HttpRequest request = this.request = (HttpRequest) msg;
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        buf.setLength(0);
        String context = queryStringDecoder.path();
        handler = handlers.get(context);
        if (handler != null) {
          handler.process(request, buf);
        }
      }
      if (msg instanceof LastHttpContent) {
        System.out.println("in channelRead LastHttpContent");
        FullHttpResponse response = new DefaultFullHttpResponse(
          HTTP_1_1,
          ((LastHttpContent) msg).decoderResult().isSuccess() ? OK : BAD_REQUEST,
          Unpooled.copiedBuffer(buf.toString(), CharsetUtil.UTF_8)
        );
        response.headers().set(CONTENT_TYPE, handler != null ? handler.getContentType() : "text/plain; charset=UTF-8");

        if (HttpUtil.isKeepAlive(request)) {
          response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
          response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
        }
        ctx.write(response);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      cause.printStackTrace();
      ctx.close();
    }
  }
}