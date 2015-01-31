package scecho

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Created by aguestuser on 1/28/15.
 */

object Main extends App {
  Try(args(0).toInt) match {
    case Failure(e) => throw new IllegalArgumentException("Argument to scecho must be a valid Integer")
    case Success(i) => Server.listenOn(Server.getChannel(i))
  }
}

object Server {

  def getChannel(port: Int): AsynchronousServerSocketChannel =
    AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(port))

  def listenOn(chn: AsynchronousServerSocketChannel) : Unit = {
    println("Scecho listening on port %s".format(chn.getLocalAddress.toString))
    val cnxn = for { cn <- accept(chn) } yield talkTo(cn)
    Await.result(cnxn, Duration.Inf)
    listenOn(chn)
  }

  def accept(listener: AsynchronousServerSocketChannel): Future[AsynchronousSocketChannel] = {
    val p = Promise[AsynchronousSocketChannel]()
    listener.accept(null, new CompletionHandler[AsynchronousSocketChannel, Void] {
      def completed(cnxn: AsynchronousSocketChannel, att: Void) = {
        println("Client connection received from %s".format(cnxn.getLocalAddress.toString))
        p success { cnxn }
      }
      def failed(e: Throwable, att: Void) = p failure { e }
    })
    p.future
  }

  def talkTo(cnxn: AsynchronousSocketChannel) : Unit = {
    for {
      input <- read(cnxn)
      done <- echoOrExit(input, cnxn)
    } yield done
  }
  
  def read(cnxn: AsynchronousSocketChannel): Future[Array[Byte]] = {
    val buf = ByteBuffer.allocate(1024) // TODO what happens to this memory allocation?
    val p = Promise[Array[Byte]]()
    cnxn.read(buf, null, new CompletionHandler[Integer, Void] {
      def completed(numRead: Integer, att: Void) = {
        println("Read %s bytes".format(numRead.toString))
        buf.flip()
        p success { buf.array() }
      }
      def failed(e: Throwable, att: Void) = p failure { e }
    })
    p.future
  }

  def echoOrExit(input: Array[Byte], cnxn: AsynchronousSocketChannel): Future[Unit] = {
    val p = Promise[Unit]()
    if (input == "exit".getBytes) p success { () }
    else p success { write(input,cnxn) }
    p.future
  }

  def write(bs: Array[Byte], cnxn: AsynchronousSocketChannel): Future[Unit] = {
    val done = (numWritten:Integer) => numWritten == bs.size
    for {
      nw <- writeOnce(bs, cnxn)
      res <- { if(done(nw)) Future.successful(()) else write(bs.drop(nw), cnxn) }
    } yield talkTo(cnxn)
  }

  def writeOnce(bs: Array[Byte], chn: AsynchronousSocketChannel): Future[Integer] = {
    val p = Promise[Integer]()
    chn.write(ByteBuffer.wrap(bs), null, new CompletionHandler[Integer, Void] {
      def completed(numWritten: Integer, att: Void) = {
        println("Echoed %s bytes".format(numWritten.toString))
        p success { numWritten }
      }
      def failed(e: Throwable, att: Void) = p failure { e }
    })
    p.future
  }
}