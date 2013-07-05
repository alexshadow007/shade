package shade.tests

import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import shade.inmemory.defaultCodecs._
import shade.InMemoryCache
import akka.util.duration._
import akka.dispatch._
import akka.util.Duration
import akka.actor.ActorSystem


@RunWith(classOf[JUnitRunner])
class InMemoryCacheSuite extends FunSuite {
  implicit val timeout = 200.millis
  val system = ActorSystem("default")
  implicit val ec = system.dispatcher

  test("add") {
    withCache("add") { cache =>
      val op1 = cache.awaitAdd("hello", Value("world"), 5.seconds)(InMemoryAnyCodec)
      assert(op1 === true)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === Some(Value("world")))

      val op2 = cache.awaitAdd("hello", Value("changed"), 5.seconds)
      assert(op2 === false)

      val changed = cache.awaitGet[Value]("hello")
      assert(changed === Some(Value("world")))
    }
  }


  test("add-null") {
    withCache("add-null") { cache =>
      val op1 = cache.awaitAdd("hello", null, 5.seconds)
      assert(op1 === false)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === None)
    }
  }

  test("get") {
    withCache("get") { cache =>
      val value = cache.awaitGet[Value]("missing")
      assert(value === None)
    }
  }

  test("set") {
    withCache("set") { cache =>
      assert(cache.awaitGet[Value]("hello") === None)

      cache.awaitSet("hello", Value("world"), 3.seconds)
      assert(cache.awaitGet[Value]("hello") === Some(Value("world")))

      cache.awaitSet("hello", Value("changed"), 3.seconds)
      assert(cache.awaitGet[Value]("hello") === Some(Value("changed")))

      Thread.sleep(3100)

      assert(cache.awaitGet[Value]("hello") === None)
    }
  }

  test("set-null") {
    withCache("set-null") { cache =>
      val op1 = cache.awaitAdd("hello", null, 5.seconds)
      assert(op1 === false)

      val stored = cache.awaitGet[Value]("hello")
      assert(stored === None)
    }
  }

  test("delete") {
    withCache("delete") { cache =>
      cache.awaitDelete("hello")
      assert(cache.awaitGet[Value]("hello") === None)

      cache.awaitSet("hello", Value("world"), 1.minute)
      assert(cache.awaitGet[Value]("hello") === Some(Value("world")))

      assert(cache.awaitDelete("hello") === true)
      assert(cache.awaitGet[Value]("hello") === None)

      assert(cache.awaitDelete("hello") === false)
    }
  }

  test("cas") {
    withCache("cas") { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      // no can do
      assert(cache.awaitCAS("some-key", Some(Value("invalid")), Value("value1"), 15.seconds) === false)
      assert(cache.awaitGet[Value]("some-key") === None)

      // set to value1
      assert(cache.awaitCAS("some-key", None, Value("value1"), 5.seconds) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value1")))

      // no can do
      assert(cache.awaitCAS("some-key", Some(Value("invalid")), Value("value1"), 15.seconds) === false)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value1")))

      // set to value2, from value1
      assert(cache.awaitCAS("some-key", Some(Value("value1")), Value("value2"), 15.seconds) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value2")))

      // no can do
      assert(cache.awaitCAS("some-key", Some(Value("invalid")), Value("value1"), 15.seconds) === false)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value2")))

      // set to value3, from value2
      assert(cache.awaitCAS("some-key", Some(Value("value2")), Value("value3"), 15.seconds) === true)
      assert(cache.awaitGet[Value]("some-key") === Some(Value("value3")))
    }
  }

  test("transformAndGet") {
    withCache("transformAndGet") { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue = Await.result(
        cache.transformAndGet[Int]("some-key", 5.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        },
        Duration.Inf
      )

      assert(incrementValue === 1)
      assert(incrementValue === 2)
      assert(incrementValue === 3)
      assert(incrementValue === 4)
      assert(incrementValue === 5)
    }
  }

  test("getAndTransform") {
    withCache("getAndTransform") { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue = Await.result(
        cache.getAndTransform[Int]("some-key", 5.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        },
        Duration.Inf
      )

      assert(incrementValue === None)
      assert(incrementValue === Some(1))
      assert(incrementValue === Some(2))
      assert(incrementValue === Some(3))
      assert(incrementValue === Some(4))
      assert(incrementValue === Some(5))
      assert(incrementValue === Some(6))
    }
  }

  test("transformAndGet-concurrent") {
    withCache("transformAndGet") { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue =
        cache.transformAndGet[Int]("some-key", 60.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        }

      val seq = Future.sequence((0 until 500).map(nr => incrementValue))
      Await.result(seq, 20.seconds)

      assert(cache.awaitGet[Int]("some-key") === Some(500))
    }
  }

  test("getAndTransform-concurrent") {
    withCache("getAndTransform") { cache =>
      cache.awaitDelete("some-key")
      assert(cache.awaitGet[Value]("some-key") === None)

      def incrementValue =
        cache.getAndTransform[Int]("some-key", 60.seconds) {
          case None => 1
          case Some(nr) => nr + 1
        }

      val seq = Future.sequence((0 until 500).map(nr => incrementValue))
      Await.result(seq, 20.seconds)

      assert(cache.awaitGet[Int]("some-key") === Some(500))
    }
  }

  def withCache[T](prefix: String)(cb: InMemoryCache => T): T = {
    val cache = InMemoryCache(1000)

    try {
      cb(cache)
    }
    finally {
      cache.shutdown()
    }
  }
}
