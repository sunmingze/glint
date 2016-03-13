package glint.vector

import akka.pattern.AskTimeoutException
import glint.SystemTest
import glint.mocking.MockBigVector
import glint.models.client.retry.RetryBigVector
import org.scalatest.{FlatSpec, Matchers}

/**
  * RetryBigVector test specification
  */
class RetryBigVectorSpec extends FlatSpec with SystemTest with Matchers {

  "A RetryBigVector" should "succesfully push and pull values" in withMaster { _ =>
    withServers(2) { servers =>
      withClient { client =>

        // Construct Vector
        val model = client.vector[Double](49, 6)
        val retryModel = new RetryBigVector[Double](model, 3)

        // Perform a push
        whenReady(retryModel.push(Array(10L), Array(0.35))) { identity }

        // Assert that the results are now on the parameter server
        val future = retryModel.pull(Array(10L, 48L))
        val newResult = whenReady(future) {
          identity
        }
        newResult should equal(Array(0.35, 0.0))
      }
    }
  }

  it should "retry when a pull fails" in {

    // Construct a mock Vector that we will intentionally fail
    val model = new MockBigVector[Double](50, 10, 0.0, _ + _)
    val retryModel = new RetryBigVector[Double](model, 3)

    // Intentionally fail the next 3 pulls, the request should still go through because of retries
    model.failNextPulls = 3

    // Attempt to push
    whenReady(retryModel.push(Array(10L), Array(0.35))) { identity }

    // Attempt to pull
    val result = whenReady(retryModel.pull(Array(10L))) { identity }
    assert(result(0) == 0.35)

  }

  it should "retry when a push fails" in {

    // Construct a mock Vector that we will intentionally fail
    val model = new MockBigVector[Double](50, 10, 0.0, _ + _)
    val retryModel = new RetryBigVector[Double](model, 3)

    // Intentionally fail the next 2 pushes, the request should still go through because of retries
    model.failNextPushes = 2

    // Attempt to push
    whenReady(retryModel.push(Array(10L), Array(0.35))) { identity }

    // Attempt to pull
    val result = whenReady(retryModel.pull(Array(10L))) { identity }
    assert(result(0) == 0.35)

  }

  it should "fail when a push retries too many times" in {

    // Construct a mock Vector that we will intentionally fail
    val model = new MockBigVector[Double](50, 10, 0.0, _ + _)
    val retryModel = new RetryBigVector[Double](model, 3)

    // Intentionally fail the next 2 pushes, the request should still go through because of retries
    model.failNextPushes = 5

    // Attempt to push
    val push = retryModel.push(Array(10L), Array(0.35))
    whenReady(push.failed) {
      ex => ex shouldBe an [AskTimeoutException]
    }

  }

  it should "fail when a pull retries too many times" in {

    // Construct a mock Vector that we will intentionally fail
    val model = new MockBigVector[Double](50, 10, 0.0, _ + _)
    val retryModel = new RetryBigVector[Double](model, 3)

    // Intentionally fail the next 2 pushes, the request should still go through because of retries
    model.failNextPulls = 5

    // Attempt to push
    whenReady(retryModel.push(Array(10L), Array(0.35))) { identity }

    // Attempt to pull and check for exception
    whenReady(retryModel.pull(Array(10L)).failed) {
      ex => ex shouldBe an [AskTimeoutException]
    }

  }

}
