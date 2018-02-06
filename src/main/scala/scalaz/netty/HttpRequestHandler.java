package scalaz.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.util.HashMap;
import java.util.Map;

import static io.netty.buffer.Unpooled.copiedBuffer;

public class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {
  private HttpRequest request;
  private final StringBuilder buf = new StringBuilder();
  private Map<String, UriHandlerBased> handlers = new HashMap<>();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
    UriHandlerBased handler = null;
    if (msg instanceof HttpRequest) {
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
      System.out.println("Read request");
      FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, this.request.method(), this.request.uri());
      ctx.fireChannelRead(request);
    }
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)  {
    ctx.writeAndFlush(new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.INTERNAL_SERVER_ERROR,
      copiedBuffer(cause.getMessage().getBytes())
    ));
  }
}