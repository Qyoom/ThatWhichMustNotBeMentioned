import scala.language.postfixOps
import scala.io.StdIn
import scala.util._
import scala.util.control.NonFatal
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

/** Contains basic data types, data structures and `Future` extensions.
 */
package object nodescala {

  /** Adds extensions methods to the `Future` companion object.
   */
  implicit class FutureCompanionOps(val f: Future.type) extends AnyVal {

    /** Returns a future that is always completed with `value`.
     */
    def always[T](value: T): Future[T] = {
      val p = Promise[T]()
      p.complete(Success(value))
      p.future
    }
    
    /** Returns a future that is never completed.
     *  This future may be useful when testing if timeout logic works correctly.
     */
    def never[T]: Future[T] = {
      val p = Promise[T]()
      // http://stackoverflow.com/questions/20265768/future-which-never-completes
      // never completed!
      p.future
    }
    
    /** Given a list of futures `fs`, returns the future holding the list of 
     *  values of all the futures from `fs`. The returned future is completed 
     *  only once all of the futures in `fs` have been completed. The values 
     *  in the list are in the same order as corresponding futures `fs`.
     *  If any of the futures `fs` fails, the resulting future also fails.
     */
    def all[T](fs: List[Future[T]]): Future[List[T]] = {
      // hint - see the lectures
      fs match {
          case Nil => Future(Nil)
          case ft :: fts => {
            ft.flatMap(t => all(fts).flatMap(ts => Future(t :: ts)))
          }
      }
    }
    
    /** Given a list of futures `fs`, returns the future holding the value 
     *  of the future from `fs` that completed first.
     *  If the first completing future in `fs` fails, then the result is 
     *  failed as well.
     *
     *  E.g.:
     *      Future.any(List(Future { 1 }, Future { 2 }, Future { throw new Exception }))
     *
     *  may return a `Future` succeeded with `1`, `2` or failed with an `Exception`.
     */
    def any[T](fs: List[Future[T]]): Future[T] = {

        val p = Promise[T]()
    
        fs map {
          _ onComplete { case x => p.tryComplete(x) }
        }
        
        p.future
    }

    /** Returns a future with a unit value that is completed after time `t`.
     */ 
    def delay(t: Duration): Future[Unit] = {
      // https://github.com/GitOutATown/async
      // TODO: Need blocking here? I don't think so...
      val futUnit = async { 
          Thread.sleep(t.toMillis)
      }
      futUnit
    }

    /** Completes this future with user input.
     */
    def userInput(message: String): Future[String] = Future {
      blocking {
        StdIn.readLine(message)
      }
    }

    /** Creates a cancellable context (i.e. CancellationTokenSource) 
     *  for an execution and runs it (i.e. calls run on it).
     *  
     *  The curried (i.e. 2nd) parameter to run is a function, f, which 
     *  in the body of the run function is called on (is passed) the
     *  CancellationToken that has been created there. At that point 
     *  f's body execution results in a Future whose computation periodically
     *  (or continually) checks whether the ComputationToken has been
     *  cancelled, in which case it exits its computation and completes.
     *  
     *  Meanwhile, the Subsription is returned to the client which
     *  can then call unsubscribe on it. See extensive comments in
     *  object CancellationTokenSource below.
     *  
     *  Note that the order of parameter processing must be right-to-left.
     *  In other words this Future being constructed via FutureCompanionOps(val f: Future.type)
     *  calls run() after the second set of apply() parameters have created
     *  the the so called cancellation context.  
     */
    def run()(f: CancellationToken => Future[Unit]): Subscription = {
        //println("~~~~ run TOP, f: " + f)
        val cts = CancellationTokenSource()
        val ct = cts.cancellationToken
        f(ct) 
        cts // returning Subscription (with it def unsubscribe)
    }
  } // end FutureCompanionOps

  /** Adds extension methods to future objects.
   */
  implicit class FutureOps[T](val f: Future[T]) extends AnyVal {

    /** Returns the result of this future if it is completed now.
     *  Otherwise, throws a `NoSuchElementException`.
     *
     *  Note: This method does not wait for the result.
     *  It is thus non-blocking.
     *  However, it is also non-deterministic -- it may throw or return a value
     *  depending on the current state of the `Future`.
     */
    def now: T = {
      // hint - use Await.result to implement this. What should be the time out ?
      // TODO: This may be where the timeout is occuring. What should the time out be? -1?
      if(f.isCompleted) Await.result(f, 0 millis)
      else throw new NoSuchElementException
    }

    /** Continues the computation of this future by taking the current future
     *  and mapping it into another future.
     *
     *  The function `cont` is called only after the current future completes.
     *  The resulting future contains a value returned by `cont`.
     */
    def continueWith[S](cont: Future[T] => S): Future[S] = {
        val p = Promise[S]()
        
        f onComplete {
            case _ => p.complete(Try(cont(f)))
        }
        
        p.future
    }

    /** Continues the computation of this future by taking the result
     *  of the current future and mapping it into another future.
     *
     *  The function `cont` is called only after the current future completes.
     *  The resulting future contains a value returned by `cont`.
     */
    // The difference from continueWith is that this is using Try.
    def continue[S](cont: Try[T] => S): Future[S] = {
      val p = Promise[S]() 
      
      f onComplete {
          case tryValue => p.complete(Try(cont(tryValue)))
      }
      
      p.future
    }

  } // end FutureOps

  /** Subscription objects are used to be able to unsubscribe
   *  from some event source.
   */
  trait Subscription {
    def unsubscribe(): Unit
  }

  object Subscription {
    /** Given two subscriptions `s1` and `s2` returns a new composite subscription
     *  such that when the new composite subscription cancels both `s1` and `s2`
     *  when `unsubscribe` is called.
     *  
     *  This is not the only Subscription constructor. The CancellationTokenSource
     *  object also constructs its companion object 
     */
    def apply(s1: Subscription, s2: Subscription) = new Subscription {
      def unsubscribe() {
        s1.unsubscribe()
        s2.unsubscribe()
      }
    }
  }

  /** Used to check if cancellation was requested.
   */
  trait CancellationToken {
    def isCancelled: Boolean
    def nonCancelled = !isCancelled
  }

  /** The `CancellationTokenSource` is a special kind of `Subscription` that
   *  returns a `cancellationToken` which is cancelled by calling `unsubscribe`.
   *
   *  After calling `unsubscribe` once, the associated `cancellationToken` will
   *  forever remain cancelled -- its `isCancelled` will return `false.
   */
  trait CancellationTokenSource extends Subscription {
    def cancellationToken: CancellationToken
  }

  /** Creates cancellation token sources.
   */
  object CancellationTokenSource {
    /**  Creates a new `CancellationTokenSource`.
      ** RW:
       * This apply block is a constructor for CancellationTokenSource which extends Subcription
       * which provides the unsubscribe method.
       * 
       * I'm betting that a new CancellationTokenSource should be created and used in the run 
       * method which must return a Subscription for the purpose of unsubscribing from a Future
       * (i.e. long-running assynchronous computation).
       * 
       * This constructor creates a Promise p which provides cancellization state for the token.
       * When the Promise p is completed by an invocation of the unsubcribe method, subsequent
       * queries to cancellationToken.isCancelled return true.
       * 
       * This constructor also invokes the construction of a member CancellationToken (from its
       * corresponding trait) which in turn provides a concrete implementation/definition for
       * its own isCancelled method.
       * 
       * isCancelled checks to see if Promise p has been completed (by a call to unsubscribe). 
       * 
       * This constructor provides the concrete implementation/definition for the unsubscribe
       * method. unsubscribe completes the Promise p for 'this' Subscription.
     */
    def apply() = new CancellationTokenSource {
      val p = Promise[Unit]()
      val cancellationToken = new CancellationToken {
        def isCancelled = p.future.value != None
      }
      def unsubscribe() {
        p.trySuccess(())
      }
    } // end apply constructor
  } // CancellationTokenSource
}

