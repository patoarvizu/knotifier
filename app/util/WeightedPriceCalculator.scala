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

class WeightedPriceCalculator extends BaseAmazonClient {

    private final val LINUX_PRODUCT_DESCRIPTION: String = "Linux/UNIX";

	def getWeightedPrice(implicit instanceType: InstanceType, availabilityZone: String): Double = {
		val currentPrice: Double = getCurrentPrice(instanceType, availabilityZone);
		val lastDayAverage: Double = getLastDayAverage(instanceType, availabilityZone);
		val threeMonthAverage: Double = getThreeMonthAverage(instanceType, availabilityZone);
		Math.floor(currentPrice * .25 + lastDayAverage * .25 + threeMonthAverage * .5 * 10000) / 10000 //Round to four decimals
	}

	private[this] def getCurrentPrice(instanceType: InstanceType, availabilityZone: String): Double = {
		getAveragePrice(instanceType, availabilityZone, Calendar.MINUTE, 1);
	};

	private[this] def getLastDayAverage(instanceType: InstanceType, availabilityZone: String): Double = {
        getAveragePrice(instanceType, availabilityZone, Calendar.HOUR, 24);
    };

    private[this] def getThreeMonthAverage(instanceType: InstanceType, availabilityZone: String): Double = {
        getAveragePrice(instanceType, availabilityZone, Calendar.MONTH, 3);
    };

    private[this] def getAveragePrice(instanceType: InstanceType, availabilityZone: String, calendarField: Int, period: Int): Double = {
    	val priceHistoryRequest: DescribeSpotPriceHistoryRequest = new DescribeSpotPriceHistoryRequest;
    	priceHistoryRequest.setInstanceTypes(Collections.singleton(instanceType.toString()));
    	val calendar: Calendar = Calendar.getInstance();
    	priceHistoryRequest.setEndTime(calendar.getTime());
    	calendar.add(calendarField, -period);
    	priceHistoryRequest.setStartTime(calendar.getTime());
    	priceHistoryRequest.setProductDescriptions(Collections.singleton(LINUX_PRODUCT_DESCRIPTION));
    	priceHistoryRequest.setAvailabilityZone(availabilityZone);
    	getAverageRecursive(0.0, 0, priceHistoryRequest);
    }

    @tailrec private[this] def getAverageRecursive(accumulatedSum: Double, numberOfResults: Int, priceHistoryRequest: DescribeSpotPriceHistoryRequest): Double = {
    	val spotPriceHistory: DescribeSpotPriceHistoryResult = ec2ClientAsync.describeSpotPriceHistory(priceHistoryRequest);
    	val sumOfThis: Double = spotPriceHistory.getSpotPriceHistory().foldLeft(0.0){(sum: Double, spotPrice: SpotPrice) => sum + spotPrice.getSpotPrice().toDouble}
    	val accumulatedSumThis: Double = accumulatedSum + sumOfThis;
    	val numberOfResultsThis: Int = numberOfResults + spotPriceHistory.getSpotPriceHistory().size();
    	if(spotPriceHistory.getNextToken().isEmpty)
    	{
    		if (numberOfResultsThis == 0) Double.MaxValue else accumulatedSumThis / numberOfResultsThis;
    	}
    	else
    	{
    		priceHistoryRequest.setNextToken(spotPriceHistory.getNextToken());
    		getAverageRecursive(accumulatedSumThis, numberOfResultsThis, priceHistoryRequest);
    	}
    }
}