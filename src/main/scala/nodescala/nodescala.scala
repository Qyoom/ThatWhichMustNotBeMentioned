package nodescala

import com.sun.net.httpserver._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import scala.collection._
import scala.collection.JavaConversions._
import java.util.concurrent.{Executor, ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

//import scala.util.{Try, Success, Failure} // Do I need this?

/** Contains utilities common to the NodeScala© framework.
 */
trait NodeScala {
  import NodeScala._

  def port: Int

  def createListener(relativePath: String): Listener

  /** Uses the response object to respond to the write the response back.
   *  The response should be written back in parts, and the method should
   *  occasionally check that server was not stopped, otherwise a very long
   *  response may take very long to finish.
   *
   *  @param exchange     the exchange used to write the response back
   *  @param token        the cancellation token
   *  @param body         the response to write back
   */
  private def respond(exchange: Exchange, token: CancellationToken, response: Response): Unit = {
      while(token.nonCancelled && response.hasNext) {
          val s = response.next()
          exchange.write(s)
      }
      exchange.close()
  }

  /** A server:
   *  1) creates and starts an http listener
   *  2) creates a cancellation token (hint: use one of the `Future` companion methods)
   *  3) as long as the token is not cancelled and there is a request from the http listener,
   *     asynchronously process that request using the `respond` method
   *
   *  @param relativePath   a relative path on which to start listening
   *  @param handler        a function mapping a request to a response
   *  @return               a subscription that can stop the server and all its asynchronous operations *entirely*
   */
  def start(relativePath: String)(handler: Request => Response): Subscription = {
      // TODO: Is this right? What about Main already doing this????
      //val server = new Default(8191)
      //val listener = server.createListener(relativePath)
      // So, no. That was not needed...just this:
      val listener = createListener(relativePath)
      val listenerSubscription = listener.start()
      
      /* create a cancellable asynchronous computation using the Future.run
       * companion method, which, while the token is not cancelled, awaits 
       * the next request from the listener and then responds to it asynchronously
       *
       * working is a Subscription, ct is the cancellationToken.
       * ct is instantiated in run and referenced by calling this anonymous function on it!
       * Which also causes the Future to be invoked (i.e. run).
       */
      val working = Future.run() { ct =>
        //println("~~~~ ct: " + ct)
        Future {
          // TODO: This may be a blocking candidate. NOPE, didn't fix it!!!
          // What is needed instead might be asynch await, and some kind of timeout code
            async {
              while (ct.nonCancelled) {
                /* as long as the token is not cancelled and there is a request from the http listener
                 * awaits the next request from the listener and then responds to it asynchronously
                 * processing that request using the `respond` method */
                //val reqExch = listener.nextRequest() // Future[(Request, Exchange)]
                val reqExch = Await.ready(listener.nextRequest(), 990 millis)
                val (req, exch) = reqExch.now//{
                respond(exch, ct, handler(req))
                //}
              } // end while
           } // end async
        }
      } // end working = Future.run()
      
      Subscription(listenerSubscription, working)
      
  } // end def start
} // end trait NodeScala


object NodeScala {

  /** A request is a multimap of headers, where each header is a key-value pair of strings.
   */
  type Request = Map[String, List[String]]

  /** A response consists of a potentially long string (e.g. a data file).
   *  To be able to process this string in parts, the response is encoded
   *  as an iterator over a subsequences of the response string.
   */
  type Response = Iterator[String]

  /** Used to write the response to the request.
   */
  trait Exchange {
    /** Writes to the output stream of the exchange.
     */
    def write(s: String): Unit

    /** Communicates that the response has ended and that there
     *  will be no further writes.
     */
    def close(): Unit

    def request: Request
  } // end object NodeScala

  object Exchange {
    def apply(exchange: HttpExchange) = new Exchange {
      val os = exchange.getResponseBody()
      exchange.sendResponseHeaders(200, 0L)

      def write(s: String) = os.write(s.getBytes)

      def close() = os.close()

      def request: Request = {
        val headers = for ((k, vs) <- exchange.getRequestHeaders) yield (k, vs.toList)
        immutable.Map() ++ headers
      }
    }
  }

  trait Listener {
    def port: Int

    def relativePath: String

    def start(): Subscription

    def createContext(handler: Exchange => Unit): Unit

    def removeContext(): Unit

    /** This Method:
     *  1) constructs an uncompleted promise
     *  2) installs an asynchronous `HttpHandler` to the `server`
     *     that deregisters itself and then completes the promise with a 
     *     request when `handle` method is invoked
     *  3) returns the future with the request
     *
     *  @return                the promise holding the pair of a request and an exchange object
     */
    def nextRequest(): Future[(Request, Exchange)] = {
      val p = Promise[(Request, Exchange)]() // These are type params, not actual data/object references!

      createContext(xchg => { // xchg is the handler
        val req = xchg.request
        removeContext()
        p.success((req, xchg))
      })

      p.future
    }
  }

  object Listener {
    class Default(val port: Int, val relativePath: String) extends Listener {
      private val s = HttpServer.create(new InetSocketAddress(port), 0)
      private val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue)
      s.setExecutor(executor)

      def start() = {
        s.start()
        new Subscription {
          def unsubscribe() = {
            s.stop(0)
            executor.shutdown()
          }
        }
      }

      def createContext(handler: Exchange => Unit) = s.createContext(relativePath, new HttpHandler {
        def handle(httpxchg: HttpExchange) = handler(Exchange(httpxchg))
      })

      def removeContext() = s.removeContext(relativePath)
    }
  } // end Listener

  /** The standard server implementation.
   */
  class Default(val port: Int) extends NodeScala {
    def createListener(relativePath: String) = new Listener.Default(port, relativePath)
  }

}
