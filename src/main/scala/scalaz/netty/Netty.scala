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

import java.util.concurrent.atomic.AtomicInteger

import concurrent._
import scalaz.netty.Server.{TaskVar, ServerState}
import stream._

import scodec.bits.{BitVector, ByteVector}

import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, ThreadFactory}

import _root_.io.netty.channel._

object Netty {

  type Handler = BitVector => BitVector
  type BatchHandler = Vector[BitVector] => BitVector

  type ServerIn = (InetSocketAddress, TaskVar[ServerState], Exchange[ByteVector, ByteVector])

  private[netty] final case class NettyThreadFactory(var name: String) extends ThreadFactory {
    private def namePrefix = name + "-netty"
    private val threadNumber = new AtomicInteger(1)
    private val group = Thread.currentThread().getThreadGroup()

    override def newThread(r: Runnable) = {
      val th = new Thread(this.group, r, s"$namePrefix-${threadNumber.getAndIncrement()}", 0L)
      th.setDaemon(true)
      th
    }
  }

  def server(bind: InetSocketAddress, config: ServerConfig = ServerConfig.Default)(implicit pool: ExecutorService): Process[Task, ServerIn] = {
    Process.await(Server(bind, config)) { server: Server =>
      server.listen onComplete Process.eval(server.shutdown).drain
    }
  }

  def connect(to: InetSocketAddress, config: ClientConfig = ClientConfig.Default)(implicit pool: ExecutorService): Process[Task, Exchange[ByteVector, ByteVector]] = {
    Process.await(Client(to, config)) { client: Client =>
      Process(Exchange(client.read, client.write)) onComplete Process.eval(client.shutdown).drain
    }
  }

  private[netty] def toTask(f: ChannelFuture)(implicit pool: ExecutorService): Task[Unit] = fork {
    Task async { (cb: (Throwable \/ Unit) => Unit) =>
      f.addListener((f: ChannelFuture) => {
        if (f.isSuccess) cb(\/-(()))
        else cb(-\/(f.cause))
      })
    }
  }

  private def fork[A](t: Task[A])(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] = {
    Task async { cb =>
      t.unsafePerformAsync { either =>
        pool.submit((() => cb(either)): Runnable)
        ()
      }
    }
  }
}