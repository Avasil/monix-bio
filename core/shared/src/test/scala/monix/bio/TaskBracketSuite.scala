/*
 * Copyright (c) 2019-2019 by The Monix Project Developers.
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
import monix.execution.exceptions.{CompositeException, DummyException}
import monix.execution.internal.Platform

import scala.util.{Failure, Success}

object TaskBracketSuite extends BaseTestSuite {
  test("equivalence with onErrorHandleWith") { implicit sc =>
    check2 { (task: WRYYY[String, Int], f: String => UIO[Unit]) =>
      val expected = task.onErrorHandleWith(e => f(e) >> WRYYY.raiseError(e))
      val received = task.bracketE(WRYYY.now) {
        case (_, Left(Some(e))) => e.fold(WRYYY.raiseFatalError, f)
        case (_, _) => UIO.unit
      }
      received <-> expected
    }
  }

  test("equivalence with flatMap + transformWith") { implicit sc =>
    check3 { (acquire: WRYYY[Int, Int], f: Int => WRYYY[Int, Int], release: Int => UIO[Unit]) =>
      val expected = acquire.flatMap { a =>
        f(a).redeemWith(
          e => release(a) >> WRYYY.raiseError(e),
          s => release(a) >> WRYYY.pure(s)
        )
      }

      val received = acquire.bracket(f)(release)
      received <-> expected
    }
  }

  test("use is protected against user error") { implicit sc =>
    val dummy = new DummyException("dummy")
    var input = Option.empty[(Int, Either[Option[Either[Throwable, Int]], Int])]

    val task = UIO.evalAsync(1).bracketE(_ => throw dummy) { (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Left(dummy))))))
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("release is evaluated on success") { implicit sc =>
    var input = Option.empty[(Int, Either[Option[Either[Throwable, Int]], Int])]

    val task = UIO.evalAsync(1).bracketE(x => UIO.evalAsync(x + 1)) { (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Right(2))))
    assertEquals(f.value, Some(Success(Right(2))))
  }

  test("release is evaluated on error") { implicit sc =>
    var input = Option.empty[(Int, Either[Option[Either[Throwable, Int]], Int])]

    val task = UIO.evalAsync(1).bracketE(_ => WRYYY.raiseError[Int](-99)) { (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Right(-99))))))
    assertEquals(f.value, Some(Success(Left(-99))))
  }

  test("release is evaluated on fatal error") { implicit sc =>
    val dummy = new DummyException("dummy")
    var input = Option.empty[(Int, Either[Option[Either[Throwable, Int]], Int])]

    val task: WRYYY[Int, Int] = UIO.evalAsync(1).bracketE[Int, Int](_ => WRYYY.raiseFatalError(dummy)) { (a, i) =>
      UIO.eval { input = Some((a, i)) }
    }

    val f = task.runToFuture
    sc.tick()

    assertEquals(input, Some((1, Left(Some(Left(dummy))))))
    assertEquals(f.value, Some(Failure(dummy)))
  }

  test("release is evaluated on cancel") { implicit sc =>
    import scala.concurrent.duration._
    var input = Option.empty[(Int, Either[Option[Either[Throwable, Int]], Int])]

    val task = UIO
      .evalAsync(1)
      .bracketE(x => UIO.evalAsync(x + 1).delayExecution(1.second)) { (a, i) =>
        UIO.eval { input = Some((a, i)) }
      }

    val f = task.runToFuture
    sc.tick()

    f.cancel()
    sc.tick(1.second)

    assertEquals(f.value, None)
    assertEquals(input, Some((1, Left(None))))
  }

  test("if both use and release throw, report release error, signal use error") { implicit sc =>
    val useError = new DummyException("use")
    val releaseError = new DummyException("release")

    val task = Task
      .evalAsync(1)
      .bracket { _ =>
        Task.raiseError[Int](useError)
      } { _ =>
        WRYYY.raiseFatalError(releaseError)
      }

    val f = task.runToFuture
    sc.tick()

    f.value match {
      case Some(Failure(error)) =>
        if (Platform.isJVM) {
          assertEquals(error, useError)
          error.getSuppressed match {
            case Array(error2) =>
              assertEquals(error2, releaseError)
            case _ =>
              fail("Unexpected suppressed errors list: " + error.getSuppressed.toList)
          }
        } else
          error match {
            case CompositeException(Seq(`useError`, `releaseError`)) =>
              () // pass
            case _ =>
              fail(s"Unexpected error: $error")
          }

      case other =>
        fail(s"Unexpected result: $other")
    }
  }

  test("bracket works with auto-cancelable run-loops") { implicit sc =>
    import concurrent.duration._

    var effect = 0
    val task = Task(1)
      .bracket(_ => Task.sleep(1.second))(_ => UIO(effect += 1))
      .executeWithOptions(_.enableAutoCancelableRunLoops)

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, None)

    f.cancel()
    assertEquals(f.value, None)
    assertEquals(effect, 1)
  }

  test("bracket is stack safe (1)") { implicit sc =>
    def loop(n: Int): Task[Unit] =
      if (n > 0)
        Task(n).bracket(n => Task(n - 1))(_ => UIO.unit).flatMap(loop)
      else
        Task.unit

    val cycles = if (Platform.isJVM) 100000 else 1000
    val f = loop(cycles).runToFuture

    sc.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("bracket is stack safe (2)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val bracket = Task.unit.bracket(_ => Task.unit)(_ => UIO.unit)
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      acc.flatMap(_ => bracket)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("bracket is stack safe (3)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      acc.bracket(_ => Task.unit)(_ => UIO.unit)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("bracket is stack safe (4)") { implicit sc =>
    val cycles = if (Platform.isJVM) 100000 else 1000
    val task = (0 until cycles).foldLeft(Task.unit) { (acc, _) =>
      Task.unit.bracket(_ => acc)(_ => UIO.unit)
    }

    val f = task.runToFuture
    sc.tick()
    assertEquals(f.value, Some(Success(Right(()))))
  }

  test("use is not evaluated on cancel") { implicit sc =>
    import scala.concurrent.duration._
    var use = false
    var release = false

    val task = Task
      .sleep(2.second)
      .bracket(_ => Task { use = true })(_ => UIO { release = true })

    val f = task.runToFuture
    sc.tick()

    f.cancel()
    sc.tick(2.second)

    assertEquals(f.value, None)
    assertEquals(use, false)

        assertEquals(release, true)
  }
}