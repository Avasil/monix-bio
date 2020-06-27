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

import monix.execution.exceptions.DummyException
import monix.execution.internal.Platform

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object TaskParTraverseUnorderedSuite extends BaseTestSuite {
  test("BIO.parTraverseUnordered should execute in parallel") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .parTraverseUnordered(seq) {
        case (i, d) =>
          BIO.evalAsync(i + 1).delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)
    s.tick(1.second)
    assertEquals(f.value, Some(Success(Seq(4, 2, 3))))
  }

  test("BIO.parTraverseUnordered should onError if one of the tasks terminates in error") { implicit s =>
    val ex = DummyException("dummy")
    val seq = Seq((1, 3), (-1, 1), (3, 2), (3, 1))
    val f = BIO
      .parTraverseUnordered(seq) {
        case (i, d) =>
          BIO
            .evalAsync(if (i < 0) throw ex else i + 1)
            .delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("BIO.parTraverseUnordered should be canceled") { implicit s =>
    val seq = Seq((1, 2), (2, 1), (3, 3))
    val f = BIO
      .parTraverseUnordered(seq) {
        case (i, d) => BIO.evalAsync(i + 1).delayExecution(d.seconds)
      }
      .runToFuture

    s.tick()
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, None)

    f.cancel()
    s.tick(1.second)
    assertEquals(f.value, None)
  }

  test("BIO.parTraverseUnordered should run over an iterable") { implicit s =>
    val count = 10
    val seq = 0 until count
    val sum = BIO.parTraverseUnordered(seq)(x => BIO.eval(x + 1)).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success((count + 1) * count / 2))
  }

  test("BIO.parTraverseUnordered should be stack-safe on handling many tasks") { implicit s =>
    val count = 10000
    val seq = for (i <- 0 until count) yield i
    val sum = BIO.parTraverseUnordered(seq)(x => BIO.eval(x)).map(_.sum)

    val result = sum.runToFuture; s.tick()
    assertEquals(result.value.get, Success(count * (count - 1) / 2))
  }

  test("BIO.parTraverseUnordered should be stack safe on success") { implicit s =>
    def fold[A](ta: BIO.Unsafe[ListBuffer[A]], next: A): BIO.Unsafe[ListBuffer[A]] =
      ta flatMap { acc =>
        BIO.parTraverseUnordered(Seq(acc, next)) { v =>
          BIO.eval(v)
        }
      } map {
        case a :: b :: Nil =>
          val (accR, valueR) = if (a.isInstanceOf[ListBuffer[_]]) (a, b) else (b, a)
          val acc = accR.asInstanceOf[ListBuffer[A]]
          val value = valueR.asInstanceOf[A]
          acc += value
        case _ =>
          throw new RuntimeException("Oops!")
      }

    def wanderSpecial[A](in: Seq[A]): BIO.Unsafe[List[A]] = {
      val init = BIO.eval(ListBuffer.empty[A])
      val r = in.foldLeft(init)(fold)
      r.map(_.result())
    }

    val count = if (Platform.isJVM) 100000 else 10000
    val tasks = 0 until count
    var result = Option.empty[Try[Int]]

    wanderSpecial(tasks)
      .map(_.sum)
      .runAsync(new BiCallback[Throwable, Int] {
        def onSuccess(value: Int): Unit =
          result = Some(Success(value))

        def onError(ex: Throwable): Unit =
          result = Some(Failure(ex))

        def onTermination(e: Throwable): Unit =
          result = Some(Failure(e))
      })

    s.tick()
    assertEquals(result, Some(Success(count * (count - 1) / 2)))
  }

  test("BIO.parTraverseUnordered should log errors if multiple errors happen") { implicit s =>
    implicit val opts = BIO.defaultOptions.disableAutoCancelableRunLoops

    val ex = DummyException("dummy1")
    var errorsThrow = 0
    val sequence = BIO.parTraverseUnordered(Seq(0, 0)) { _ =>
      BIO
        .raiseError(ex)
        .executeAsync
        .doOnFinish { x =>
          if (x.isDefined) errorsThrow += 1
          UIO.unit
        }
        .uncancelable
    }

    val result = sequence.runToFutureOpt
    s.tick()

    assertEquals(result.value, Some(Failure(ex)))
    assertEquals(s.state.lastReportedError, ex)
    assertEquals(errorsThrow, 2)
  }

  test("BIO.parTraverseUnordered runAsync multiple times") { implicit s =>
    var effect = 0
    val task1 = BIO.evalAsync {
      effect += 1; 3
    }.memoize

    val task2 = task1 map { x =>
      effect += 1; x + 1
    }

    val task3 = BIO.parTraverseUnordered(List(0, 0, 0)) { _ =>
      task2
    }

    val result1 = task3.runToFuture; s.tick()
    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3)

    val result2 = task3.runToFuture; s.tick()
    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
    assertEquals(effect, 1 + 3 + 3)
  }

  test("BIO.parTraverseUnordered should wrap exceptions in the function") { implicit s =>
    val ex = DummyException("dummy")
    val task1 = BIO.parTraverseUnordered(Seq(0)) { _ =>
      throw ex
    }

    val result1 = task1.runToFuture; s.tick()
    assertEquals(result1.value, Some(Failure(ex)))
  }
}
