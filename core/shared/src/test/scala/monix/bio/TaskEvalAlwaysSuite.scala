/*
 * Copyright (c) 2019-2020 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
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

package monix.bio

import cats.laws._
import cats.laws.discipline._
import monix.execution.exceptions.DummyException

import scala.util.{Failure, Success}

object TaskEvalAlwaysSuite extends BaseTestSuite {
  test("BIO.eval should work synchronously") { implicit s =>
    var wasTriggered = false
    def trigger(): String = { wasTriggered = true; "result" }

    val task = BIO.eval(trigger())
    assert(!wasTriggered, "!wasTriggered")

    val f = task.runToFuture
    assert(wasTriggered, "wasTriggered")
    assertEquals(f.value, Some(Success("result")))
  }

  test("BIO.eval should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = BIO.eval[Int](if (1 == 1) throw ex else 1).runToFuture

    assertEquals(f.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }

  test("BIO.eval is equivalent with BIO.evalOnce on first run") { implicit s =>
    check1 { a: Int =>
      val t1 = {
        var effect = 100
        BIO.eval { effect += 100; effect + a }
      }

      val t2 = {
        var effect = 100
        BIO.evalOnce { effect += 100; effect + a }
      }

      t1 <-> t2
    }
  }

  test("BIO.eval.flatMap should be equivalent with BIO.eval") { implicit s =>
    val ex = DummyException("dummy")
    val t = BIO.eval[Int](if (1 == 1) throw ex else 1).flatMap(BIO.now)
    check(t <-> BIO.raiseError(ex))
  }

  test("BIO.eval.flatMap should protect against user code") { implicit s =>
    val ex = DummyException("dummy")
    val t = BIO.eval(1).flatMap[Throwable, Int](_ => throw ex)
    check(t <-> BIO.terminate(ex))
  }

  test("BIO.eval.flatMap should be tail recursive") { implicit s =>
    def loop(n: Int, idx: Int): BIO[Throwable, Int] =
      BIO.eval(idx).flatMap { _ =>
        if (idx < n) loop(n, idx + 1).map(_ + 1)
        else
          BIO.eval(idx)
      }

    val iterations = s.executionModel.recommendedBatchSize * 20
    val f = loop(iterations, 0).runToFuture
    s.tick()
    assertEquals(f.value, Some(Success(iterations * 2)))
  }

  test("BIO.eval should not be cancelable") { implicit s =>
    val t = BIO.eval(10)
    val f = t.runToFuture
    f.cancel()
    s.tick()
    assertEquals(f.value, Some(Success(10)))
  }

  test("BIO.eval.coeval") { implicit s =>
    val result = BIO.eval(100).runSyncStep
    assertEquals(result, Right(100))
  }

  test("BIO.eval.flatMap should protect against user code errors") { implicit s =>
    val ex = DummyException("dummy")
    val task: BIO.Unsafe[Int] = BIO.eval(1).flatMap(_ => throw ex)
    assertEquals(task.redeemCause(ex => Left(ex.toThrowable), v => Right(v)).runSyncStep, Right(Left(ex)))
  }

  test("BIO.delay is an alias for BIO.eval") { implicit s =>
    var effect = 0
    val ts = BIO.delay { effect += 1; effect }

    assertEquals(ts.runToFuture.value, Some(Success(1)))
    assertEquals(ts.runToFuture.value, Some(Success(2)))
    assertEquals(ts.runToFuture.value, Some(Success(3)))

    val dummy = new DummyException("dummy")
    val te = BIO.delay { throw dummy }
    assertEquals(te.runToFuture.value, Some(Failure(dummy)))
  }

  test("BIO.evalTotal should protect against unexpected errors") { implicit s =>
    val ex = DummyException("dummy")
    val f = UIO.eval[Int](throw ex).redeemCause(_ => 10, identity).runToFuture
    val g = UIO.eval[Int](throw ex).onErrorHandle(_ => 10).runToFuture

    assertEquals(f.value, Some(Success(10)))
    assertEquals(g.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, null)
  }
}
