package io.example.filetransfer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


class Fetcher implements Runnable {
  public static final int BUFSIZE = 1024*1024;
  private File file;
  private ByteBuf buf;
  private ChannelHandlerContext ctx;
  private AtomicBoolean done;
  private AtomicBoolean suspended;
  private byte[] b;
  public Fetcher(ChannelHandlerContext ctx, File file, ByteBuf buf) {
    this.file = file;
    this.done = new AtomicBoolean(false);
    this.suspended = new AtomicBoolean(false);
    this.buf = buf;
    this.ctx = ctx;
    this.b = new byte[BUFSIZE];
  }

  public void run() {
    try {
        long bytes = 0;
        FileInputStream fip = new FileInputStream(file);
        while (true) {
          if (!buf.isWritable(BUFSIZE)) {
            Thread.sleep(10); //so we don't hog the cpu
            continue;
          }
          int sz = fip.read(b);
          if (sz == -1)
            break;
          buf.writeBytes(b, 0, sz);
          bytes += sz;
          System.out.println("Wrote " + bytes + " bytes");
          if (suspended.get()) {
              suspended.set(false);
              ChunkedWriteHandler chunker = (ChunkedWriteHandler) ctx.pipeline().get("chunker");
              System.out.println("Resuming transfer");
              chunker.resumeTransfer();
          }
        }
        System.out.println("Done reading");
        done.set(true);
    } catch (Exception e) {
      e.printStackTrace(System.out);
      System.out.println("Caught Exception");
    }
  }

  public void suspend() {
    suspended.set(true);
  }

  public boolean isDone() {
    return done.get();
  }

  public ByteBuf getBuf() {
    return buf;
  }
}

public class FileServer implements Runnable {
  private int port;
  private static final String sourceDir = "/tmp/server/";
  private ExecutorService executor;

  public static String getSourceDir() {
    return sourceDir;
  }

  public static void main(String[] args)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(10);
    executor.execute(new FileServer(8088, executor));
    //executor.shutdown();
    //executor.awaitTermination(1, TimeUnit.DAYS);
  }

  public FileServer(int port, ExecutorService executor) {
    this.port = port;
    this.executor = executor;
    File dir = new File(sourceDir);
    if (!dir.exists()) {
      dir.mkdir();
    }
  }

  public void run() {

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup(1);

    try {
       ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 100)
          .handler(new LoggingHandler(LogLevel.INFO)).childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        public void initChannel(SocketChannel ch)
            throws Exception {
          ch.pipeline().addLast("codec", new HttpServerCodec())
                       .addLast("chunker", new ChunkedWriteHandler())
                       .addLast("custom", new MyFileServerHandler(executor));
        }
      });
      ChannelFuture f = b.bind(port).sync();
      System.out.println("Open your web browser and navigate to " +
          "http://127.0.0.1:" + port + '/');
      f.channel().closeFuture().sync();
    } catch (Exception e) {
    }finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}

class MyFileServerHandler extends SimpleChannelInboundHandler<HttpObject> {
  private ExecutorService executor;

  public MyFileServerHandler(ExecutorService executor) {
    this.executor = executor;
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg)
      throws Exception {
    ctx.getClass();
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (!request.getDecoderResult().isSuccess()) {
        sendError(ctx, HttpResponseStatus.BAD_REQUEST);
        return;
      }

      if (request.getMethod() != HttpMethod.GET) {
        sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
        return;
      }

      final String uri = request.getUri();
      final String path = FileServer.getSourceDir() + uri;
      System.out.println(path);
      if (path == null) {
        sendError(ctx, HttpResponseStatus.FORBIDDEN);
        return;
      }

      File file = new File(path);
      if (file.isHidden() || !file.exists()) {
        sendError(ctx, HttpResponseStatus.NOT_FOUND);
        return;
      }

      if (!file.isFile()) {
        sendError(ctx, HttpResponseStatus.FORBIDDEN);
        return;
      }

      Fetcher f = new Fetcher(ctx, file, Unpooled.buffer(Fetcher.BUFSIZE));

      executor.execute(f);

      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      //HttpHeaders.setContentLength(response, fileLength);
      setContentTypeHeader(response);
      response.headers().set(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED);
      if (HttpHeaders.isKeepAlive(request)) {
        response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      }

      // Write the initial line and the header.
      ctx.write(response);

      // Write the content.
      ChannelFuture sendFileFuture;
      ChannelFuture lastContentFuture;
      //sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
      sendFileFuture = ctx.writeAndFlush(new MyHttpChunkedInput(f), ctx.newProgressivePromise());
      // HttpChunkedInput will write the end marker (LastHttpContent) for us.
      lastContentFuture = sendFileFuture;

      sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
        @Override
        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
          if (total < 0) { // total unknown
            System.out.println(future.channel() + " Transfer progress: " + progress);
          } else {
            System.out.println(future.channel() + " Transfer progress: " + progress + " / " + total);
          }
        }


        @Override
        public void operationComplete(ChannelProgressiveFuture future) {
          System.out.println(future.channel() + " Transfer complete.");
        }
      });

      // Decide whether to close the connection or not.
      if (!HttpHeaders.isKeepAlive(request)) {
        // Close the connection when the whole content is written out.
        lastContentFuture.addListener(ChannelFutureListener.CLOSE);
      }
    }
  }

  private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
        Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream");

    // Close the connection as soon as the error message is sent.
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static void setContentTypeHeader(HttpResponse response) {
    response.headers()
        .set(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream");
  }
}

class MyHttpChunkedInput implements ChunkedInput<HttpContent> {
  Fetcher fetcher;
  private final LastHttpContent lastHttpContent;
  private boolean sentLastChunk;
  MyHttpChunkedInput(Fetcher f) {
    this.fetcher = f;
    this.lastHttpContent = LastHttpContent.EMPTY_LAST_CONTENT;
  }
  @Override
  public void close() throws Exception {
  }

  @Override
  public HttpContent readChunk(ChannelHandlerContext ctx) throws Exception {
    if (fetcher.isDone()) {
      if (sentLastChunk) {
        System.out.println("Returned eof");
        return null;
      } else {
        sentLastChunk = true;
        System.out.println("Returned lastHttpContent");
        return lastHttpContent;
      }
    }
    ByteBuf buf = fetcher.getBuf();
    buf.discardReadBytes();
    if (buf.readableBytes() > 0) {
      ByteBuf slice = buf.readBytes(buf.readableBytes());
      //ByteBuf slice = buf.retain().readSlice(buf.readableBytes());
      System.out.println("Returning slice");
      return new DefaultHttpContent(slice);
    } else {
      System.out.println("Returning null");
      fetcher.suspend();
      return null;
    }
  }

  @Override
  public boolean isEndOfInput() throws Exception {
    boolean ret = fetcher.isDone() && sentLastChunk;
    return ret;
  }

}

