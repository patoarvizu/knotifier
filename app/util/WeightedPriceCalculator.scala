package util

import com.amazonaws.services.ec2.model.InstanceType
import scala.collection.Map
import model.SpotPriceInfo
import java.util.Calendar
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest
import java.util.Collections
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult
import com.amazonaws.services.ec2.model.SpotPrice
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import play.libs.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import play.Logger
import scala.annotation.tailrec

object WeightedPriceCalculator extends AmazonClient {

    private final val LinuxProductDescription: String = "Linux/UNIX"

	def getWeightedPrice(implicit instanceType: InstanceType, availabilityZone: String): Double = {
	    
		val currentPriceFut = Future {
		    getCurrentPrice(instanceType, availabilityZone)
		}
		val lastDayAverageFut = Future {
		    getLastDayAverage(instanceType, availabilityZone)
		}
		val threeMonthAverageFut = Future {
		    getThreeMonthAverage(instanceType, availabilityZone)
		}
		
		Await.result (
		for {
		    currentPrice <- currentPriceFut
		    lastDayAverage <- lastDayAverageFut
		    threeMonthAverage <- threeMonthAverageFut
		}
		yield Math.floor(currentPrice.getOrElse(lastDayAverage.get) * .25 + lastDayAverage.get * .25 + threeMonthAverage.get * .5 * 10000) / 10000 //Round to four decimals
		, Duration.Inf
		)
	}

	def getCurrentPrice(instanceType: InstanceType, availabilityZone: String) = getAveragePrice(instanceType, availabilityZone, Calendar.MINUTE, 1)

	private[this] def getLastDayAverage(instanceType: InstanceType, availabilityZone: String) = getAveragePrice(instanceType, availabilityZone, Calendar.HOUR, 24)

    private[this] def getThreeMonthAverage(instanceType: InstanceType, availabilityZone: String) = getAveragePrice(instanceType, availabilityZone, Calendar.MONTH, 3)

    private[this] def getAveragePrice(instanceType: InstanceType, availabilityZone: String, calendarField: Int, period: Int): Option[Double] = {
    	val priceHistoryRequest: DescribeSpotPriceHistoryRequest = new DescribeSpotPriceHistoryRequest
    	priceHistoryRequest.setInstanceTypes(Collections.singleton(instanceType.toString))
    	val calendar: Calendar = Calendar.getInstance
    	priceHistoryRequest.setEndTime(calendar.getTime)
    	calendar.add(calendarField, -period)
    	priceHistoryRequest.setStartTime(calendar.getTime)
    	priceHistoryRequest.setProductDescriptions(Collections.singleton(LinuxProductDescription))
    	priceHistoryRequest.setAvailabilityZone(availabilityZone)
    	getAverageRecursive(0.0, 0, priceHistoryRequest)
    }

    @tailrec private[this] def getAverageRecursive(accumulatedSum: Double, numberOfResults: Int, priceHistoryRequest: DescribeSpotPriceHistoryRequest): Option[Double] = {
    	val spotPriceHistory: DescribeSpotPriceHistoryResult = ec2ClientAsync.describeSpotPriceHistory(priceHistoryRequest)
    	val sumOfThis: Double = spotPriceHistory.getSpotPriceHistory.map{spotPrice: SpotPrice => spotPrice.getSpotPrice.toDouble}.sum
    	val accumulatedSumThis: Double = accumulatedSum + sumOfThis
    	val numberOfResultsThis: Int = numberOfResults + spotPriceHistory.getSpotPriceHistory.size
    	if(spotPriceHistory.getNextToken.isEmpty)
    	{
    		if (numberOfResultsThis == 0) None else Some(accumulatedSumThis / numberOfResultsThis)
    	}
    	else
    	{
    		priceHistoryRequest.setNextToken(spotPriceHistory.getNextToken)
    		getAverageRecursive(accumulatedSumThis, numberOfResultsThis, priceHistoryRequest)
    	}
    }
}