package shade.memcached

import internals._
import net.spy.memcached.{FailureMode => SpyFailureMode, _}
import net.spy.memcached.ConnectionFactoryBuilder.{Protocol => SpyProtocol}
import net.spy.memcached.auth.{PlainCallbackHandler, AuthDescriptor}
import java.util.concurrent.TimeUnit
import akka.actor.Scheduler
import shade.{TransformOverflowException, UnhandledStatusException, TimeoutException, Memcached}
import akka.dispatch._
import akka.util.{Duration, FiniteDuration}

/**
 * Memcached client implementation based on SpyMemcached.
 *
 * See the parent trait (Cache) for API docs.
 */
class MemcachedImpl(config: Configuration, scheduler: Scheduler, ec: ExecutionContext) extends Memcached {
  private[this] implicit val context = ec

  /**
   * Adds a value for a given key, if the key doesn't already exist in the cache store.
   *
   * If the key already exists in the cache, the future returned result will be false and the
   * current value will not be overridden. If the key isn't there already, the value
   * will be set and the future returned result will be true.
   *
   * The expiry time can be Duration.Inf (infinite duration).
   *
   * @return either true, in case the value was set, or false otherwise
   */
  def asyncAdd[T](key: String, value: T, exp: Duration)(implicit codec: Codec[T]): Future[Boolean] =
    if (value != null)
      instance.realAsyncAdd(withPrefix(key), codec.serialize(value), 0, exp, config.operationTimeout) map {
        case SuccessfulResult(givenKey, Some(_)) =>
          true
        case SuccessfulResult(givenKey, None) =>
          false
        case failure: FailedResult =>
          throwExceptionOn(failure)
      }
    else
      Promise.successful(false)

  /**
   * Sets a (key, value) in the cache store.
   *
   * The expiry time can be Duration.Inf (infinite duration).
   */
  def asyncSet[T](key: String, value: T, exp: Duration)(implicit codec: Codec[T]): Future[Unit] = {
    if (value != null)
      instance.realAsyncSet(withPrefix(key), codec.serialize(value), 0, exp, config.operationTimeout) map {
        case SuccessfulResult(givenKey, _) =>
          ()
        case failure: FailedResult =>
          throwExceptionOn(failure)
      }
    else
      Promise.successful(())
  }

  /**
   * Deletes a key from the cache store.
   *
   * @return true if a key was deleted or false if there was nothing there to delete
   */
  def asyncDelete(key: String): Future[Boolean] =
    instance.realAsyncDelete(withPrefix(key), config.operationTimeout) map {
      case SuccessfulResult(givenKey, result) =>
        result
      case failure: FailedResult =>
        throwExceptionOn(failure)
    }

  /**
   * Fetches a value from the cache store.
   *
   * @return Some(value) in case the key is available, or None otherwise (doesn't throw exception on key missing)
   */
  def asyncGet[T](key: String)(implicit codec: Codec[T]): Future[Option[T]] =
    instance.realAsyncGet(withPrefix(key), config.operationTimeout) map {
      case SuccessfulResult(givenKey, option) =>
        option.map(codec.deserialize)
      case failure: FailedResult =>
        throwExceptionOn(failure)
    }

  def getOrElse[T](key: String, default: => T)(implicit codec: Codec[T]): Future[T] =
    asyncGet[T](key) map {
      case Some(value) => value
      case None => default
    }

  /**
   * Compare and set.
   *
   * @param expecting should be None in case the key is not expected, or Some(value) otherwise
   * @param exp can be Duration.Inf (infinite) for not setting an expiration
   * @return either true (in case the compare-and-set succeeded) or false otherwise
   */
  def asyncCAS[T](key: String, expecting: Option[T], newValue: T, exp: Duration)(implicit codec: Codec[T]): Future[Boolean] =
    expecting match {
      case None =>
        asyncAdd[T](key, newValue, exp)

      case Some(expectingValue) =>
        instance.realAsyncGets(withPrefix(key), config.operationTimeout) flatMap {
          case SuccessfulResult(givenKey, None) =>
            Promise.successful(false)

          case SuccessfulResult(givenKey, Some((currentData, casID))) =>
            if (codec.deserialize(currentData) == expectingValue)
              instance.realAsyncCAS(withPrefix(key), casID, 0, codec.serialize(newValue), exp, config.operationTimeout) map {
                case SuccessfulResult(_, bool) =>
                  bool
                case failure: FailedResult =>
                  throwExceptionOn(failure)
              }
            else
              Promise.successful(false)
          case failure: FailedResult =>
            throwExceptionOn(failure)
        }
    }

  /**
   * Used by both transformAndGet and getAndTransform for code reusability.
   *
   * @param f is the function that dictates what gets returned (either the old or the new value)
   */
  private[this] def genericTransform[T, R](key: String, exp: Duration, cb: Option[T] => T)(f: (Option[T], T) => R)(implicit codec: Codec[T]): Future[R] = {
    val keyWithPrefix = withPrefix(key)

    /**
     * Inner function used for retrying compare-and-set operations
     * with a maximum threshold of retries.
     *
     * @throws TransformOverflowException in case the maximum number of
     *                                    retries is reached
     */
    def loop(retry: Int): Future[R] = {
      if (config.maxTransformCASRetries > 0 && retry >= config.maxTransformCASRetries)
        throw new TransformOverflowException(key)

      instance.realAsyncGets(keyWithPrefix, config.operationTimeout) flatMap {
        case SuccessfulResult(_, None) =>
          val result = cb(None)
          asyncAdd(key, result, exp) flatMap {
            case true =>
              Promise.successful(f(None, result))
            case false =>
              loop(retry + 1)
          }
        case SuccessfulResult(_, Some((current, casID))) =>
          val currentOpt = Some(codec.deserialize(current))
          val result = cb(currentOpt)

          instance.realAsyncCAS(keyWithPrefix, casID, 0, codec.serialize(result), exp, config.operationTimeout) flatMap {
            case SuccessfulResult(_, true) =>
              Promise.successful(f(currentOpt, result))
            case SuccessfulResult(_, false) =>
              loop(retry + 1)
            case failure: FailedResult =>
              throwExceptionOn(failure)
          }

        case failure: FailedResult =>
          throwExceptionOn(failure)
      }
    }

    loop(0)
  }

  /**
   * Transforms the given key and returns the new value.
   *
   * The cb callback receives the current value
   * (None in case the key is missing or Some(value) otherwise)
   * and should return the new value to store.
   *
   * The method retries until the compare-and-set operation succeeds, so
   * the callback should have no side-effects.
   *
   * This function can be used for atomic incrementers and stuff like that.
   *
   * @return the new value
   */
  def transformAndGet[T](key: String, exp: Duration)(cb: (Option[T]) => T)(implicit codec: MemcachedImpl#Codec[T]): Future[T] =
    genericTransform(key, exp, cb) {
      case (oldValue, newValue) => newValue
    }

  /**
   * Transforms the given key and returns the old value as an Option[T]
   * (None in case the key wasn't in the cache or Some(value) otherwise).
   *
   * The cb callback receives the current value
   * (None in case the key is missing or Some(value) otherwise)
   * and should return the new value to store.
   *
   * The method retries until the compare-and-set operation succeeds, so
   * the callback should have no side-effects.
   *
   * This function can be used for atomic incrementers and stuff like that.
   *
   * @return the old value
   */
  def getAndTransform[T](key: String, exp: Duration)(cb: (Option[T]) => T)(implicit codec: MemcachedImpl#Codec[T]): Future[Option[T]] =
    genericTransform(key, exp, cb) {
      case (oldValue, newValue) => oldValue
    }

  def shutdown() {
    instance.shutdown(3, TimeUnit.SECONDS)
  }

  private[this] def throwExceptionOn(failure: FailedResult) = failure match {
    case FailedResult(k, TimedOutStatus) =>
      throw new TimeoutException(withoutPrefix(k))
    case FailedResult(k, CancelledStatus) =>
      throw new TimeoutException(withoutPrefix(k))
    case FailedResult(k, unhandled) =>
      throw new UnhandledStatusException(
        "For key %s - %s".format(withoutPrefix(k), unhandled.getClass.getName))
  }

  @inline
  private[this] def withPrefix(key: String): String =
    if (prefix.isEmpty)
      key
    else
      prefix + "-" + key

  @inline
  private[this] def withoutPrefix[T](key: String): String = {
    if (!prefix.isEmpty && key.startsWith(prefix + "-"))
      key.substring(prefix.length + 1)
    else
      key
  }

  private[this] val prefix = config.keysPrefix.getOrElse("")
  private[this] val instance = {
    System.setProperty("net.spy.log.LoggerImpl",
      "shade.memcached.internals.Slf4jLogger")

    val conn = {
      val builder = new ConnectionFactoryBuilder()
        .setProtocol(
          if (config.protocol == Protocol.Binary)
            SpyProtocol.BINARY
          else
            SpyProtocol.TEXT
        )
        .setDaemon(true)
        .setFailureMode(config.failureMode match {
          case FailureMode.Retry =>
            SpyFailureMode.Retry
          case FailureMode.Cancel =>
            SpyFailureMode.Cancel
          case FailureMode.Redistribute =>
            SpyFailureMode.Redistribute
        })

      val withTimeout = config.operationTimeout match {
        case duration: FiniteDuration =>
          builder.setOpTimeout(config.operationTimeout.toMillis)
        case _ =>
          builder
      }

      val withAuth = config.authentication match {
        case Some(credentials) =>
          withTimeout.setAuthDescriptor(
            new AuthDescriptor(Array("PLAIN"),
              new PlainCallbackHandler(credentials.username, credentials.password)))
        case None =>
          withTimeout
      }

      withAuth
    }

    import scala.collection.JavaConverters._
    val addresses = AddrUtil.getAddresses(config.addresses).asScala
    new SpyMemcachedIntegration(conn.build(), addresses, scheduler)
  }
}

