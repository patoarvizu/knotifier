package actors

import scala.collection.immutable.{Map => ImmutableMap}
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import com.amazonaws.services.ec2.AmazonEC2AsyncClient
import com.amazonaws.services.ec2.model.InstanceType

import model.SpotPriceInfo
import util.WeightedPriceCalculator

class PriceMonitorTest extends Specification with Mockito {
    isolated

    val priceMonitorSpy = spy(new PriceMonitor)
    val mockEC2Client = mock[AmazonEC2AsyncClient]
    val mockWeightedPriceCalculator = mock[WeightedPriceCalculator]
    priceMonitorSpy.weightedPriceCalculator returns mockWeightedPriceCalculator
    priceMonitorSpy.ec2ClientAsync returns mockEC2Client

    "The price monitor actor" should {
        "Populate the price monitor cache" in {
            mockWeightedPriceCalculator.getWeightedPrice(any[InstanceType], anyString) returns 1.00
            priceMonitorSpy.monitorSpotPrices foreach { future: Future[Unit] => Await.result(future, Duration.Inf) }
            priceMonitorSpy.getWeightedPrices.size mustEqual(9)
        }
    }

    "The price information local cache" should {
        "Be immutable" in {
            priceMonitorSpy.getWeightedPrices must beAnInstanceOf[ImmutableMap[InstanceType, SpotPriceInfo]]
        }
    }
}