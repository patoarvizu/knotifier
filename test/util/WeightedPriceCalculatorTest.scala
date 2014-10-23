package util

import org.specs2.mutable.Specification
import com.amazonaws.services.ec2.model.InstanceType
import org.specs2.mock.Mockito
import java.util.ArrayList
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryResult
import com.amazonaws.services.ec2.model.SpotPrice
import com.amazonaws.services.ec2.AmazonEC2AsyncClient
import com.amazonaws.services.ec2.model.DescribeSpotPriceHistoryRequest
import java.util.Calendar
import org.specs2.matcher.Hamcrest
import org.specs2.matcher.Matcher
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.CustomMatcher
import org.mockito.ArgumentMatcher

class WeightedPriceCalculatorTest extends Specification with Mockito with Hamcrest {
    isolated
    trait IsTimeRangeRequest extends ArgumentMatcher[DescribeSpotPriceHistoryRequest] {
        def matches(request: Any): Boolean = {
            val theRequest = request.asInstanceOf[DescribeSpotPriceHistoryRequest]
            val startTime = theRequest.getStartTime
            val endTime = theRequest.getEndTime
            val startCalendar = Calendar.getInstance
            startCalendar.setTime(startTime)
            startCalendar.add(getCalendarField, getPeriod)
            endTime.compareTo(startCalendar.getTime) == 0
        }
        def getCalendarField: Int
        def getPeriod: Int
    }
    object IsRequestOfLastPrice extends IsTimeRangeRequest{
        def getCalendarField: Int = Calendar.MINUTE
        def getPeriod: Int = 1
    }
    object IsRequestOfLastDay extends IsTimeRangeRequest{
        def getCalendarField: Int = Calendar.HOUR
        def getPeriod: Int = 24
    }
    object IsRequestOfLastThreeMonths extends IsTimeRangeRequest{
        def getCalendarField: Int = Calendar.MONTH
        def getPeriod: Int = 3
    }
    val weightedPriceCalculator = spy(new WeightedPriceCalculator)
    val mockEC2Client = mock[AmazonEC2AsyncClient]
    weightedPriceCalculator.ec2ClientAsync returns mockEC2Client
    
    "Getting the current price" should {
        val fakeSpotPriceHistory = new ArrayList[SpotPrice]
        val fakeSpotPriceHistoryResult = new DescribeSpotPriceHistoryResult
        mockEC2Client.describeSpotPriceHistory(any[DescribeSpotPriceHistoryRequest]) returns fakeSpotPriceHistoryResult
        "Return None if no data point is available in the last minute" in {
            fakeSpotPriceHistoryResult.setSpotPriceHistory(fakeSpotPriceHistory)
            val currentPrice = weightedPriceCalculator.getCurrentPrice(InstanceType.C3Large, "us-east-1a")
            currentPrice must beNone
        }
        "Return the last price if there's only one data point in the last minute" in {
            val spotPrice = new SpotPrice
            spotPrice.setSpotPrice("0.50")
            fakeSpotPriceHistory.add(spotPrice)
            fakeSpotPriceHistoryResult.setSpotPriceHistory(fakeSpotPriceHistory)
            val currentPrice = weightedPriceCalculator.getCurrentPrice(InstanceType.C3Large, "us-east-1a")
            currentPrice must beSome(0.50)
        }
        "Return an average of all prices in the last minute if there are multiple" in {
            val spotPrice1 = new SpotPrice
            spotPrice1.setSpotPrice("0.25")
            fakeSpotPriceHistory.add(spotPrice1)
            val spotPrice2 = new SpotPrice
            spotPrice2.setSpotPrice("0.75")
            fakeSpotPriceHistory.add(spotPrice2)
            fakeSpotPriceHistoryResult.setSpotPriceHistory(fakeSpotPriceHistory)
            val currentPrice = weightedPriceCalculator.getCurrentPrice(InstanceType.C3Large, "us-east-1a")
            currentPrice must beSome(0.50)
        }
    }
    "Getting a weighted price" should {
        "Return a weighted price of lastPrice * .25 + lastDayAverage * .25 + threeMonthAverage * .5" in {
            val fakeSpotPriceHistoryResultCurrent = new DescribeSpotPriceHistoryResult().withSpotPriceHistory(new SpotPrice().withSpotPrice("0.15"))
            doAnswer({ any => fakeSpotPriceHistoryResultCurrent }).when(mockEC2Client).describeSpotPriceHistory(argThat(IsRequestOfLastPrice))
            val fakeSpotPriceHistoryResultLastDay = new DescribeSpotPriceHistoryResult().withSpotPriceHistory(new SpotPrice().withSpotPrice("0.20"))
            doAnswer({ any => fakeSpotPriceHistoryResultLastDay }).when(mockEC2Client).describeSpotPriceHistory(argThat(IsRequestOfLastDay))
            val fakeSpotPriceHistoryResultLastThreeMonths = new DescribeSpotPriceHistoryResult().withSpotPriceHistory(new SpotPrice().withSpotPrice("0.10"))
            doAnswer({ any => fakeSpotPriceHistoryResultLastThreeMonths }).when(mockEC2Client).describeSpotPriceHistory(argThat(IsRequestOfLastThreeMonths))
            val weightedPrice = weightedPriceCalculator.getWeightedPrice(InstanceType.C3Large, "us-east-1a")
            weightedPrice mustEqual(0.1375)
        }
    }
}