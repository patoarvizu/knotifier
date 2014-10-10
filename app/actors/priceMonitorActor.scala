package actors

import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.ec2.model.SpotPrice
import model.SpotPriceInfo
import java.util.Date
import scala.collection.concurrent.TrieMap
import scala.collection.concurrent.Map
import scala.collection.immutable.{HashMap => ImmutableHashMap}
import scala.collection.immutable.{Map => ImmutableMap}
import scala.concurrent.Future
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.model.InstanceType._
import model.SpotPriceInfo
import play.Logger
import util.WeightedPriceCalculator
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.StringBuilder

object PriceMonitor extends AmazonClient {

    private final val lowestWeightedPrices: Map[InstanceType, SpotPriceInfo] = new TrieMap[InstanceType, SpotPriceInfo]
    private final val instanceTypes: Set[InstanceType] = Set(C32xlarge, C34xlarge, C38xlarge, C3Large, C3Xlarge, M32xlarge, M3Large, M3Medium, M3Xlarge)
    private final val availabilityZones: Set[String] = Set("us-east-1a", "us-east-1d")

    def getWeightedPrices: ImmutableMap[InstanceType, SpotPriceInfo] = new ImmutableHashMap[InstanceType, SpotPriceInfo]() ++ lowestWeightedPrices

    def monitorSpotPrices = {
        for {
            instanceType <- instanceTypes
            availabilityZone <- availabilityZones
        } {
            Future { setWeightedPrice(instanceType, availabilityZone) }
        }
    }

    private[this] def setWeightedPrice(instanceType: InstanceType, availabilityZone: String) = {
        val weightedPrice: Double = WeightedPriceCalculator.getWeightedPrice(instanceType, availabilityZone)
        if(!lowestWeightedPrices.contains(instanceType))
            lowestWeightedPrices(instanceType) = SpotPriceInfo(instanceType, availabilityZone, weightedPrice)
        else if(lowestWeightedPrices(instanceType).price > weightedPrice)
        {
            val spotPriceInfo: SpotPriceInfo = lowestWeightedPrices(instanceType)
            lowestWeightedPrices(instanceType) = spotPriceInfo.copy(availabilityZone=availabilityZone, price=weightedPrice)
        }
    }

    def printPrices =
    {
        val pricesStringBuilder:StringBuilder = new StringBuilder
        pricesStringBuilder.append(s"\n${new Date().toString}\n")
        lowestWeightedPrices.values foreach { spotPrice: SpotPriceInfo =>
            pricesStringBuilder.append(s" --- Price for instance type ${spotPrice.instanceType} in availability zone ${spotPrice.availabilityZone} is ${spotPrice.price}\n")
        }
        pricesStringBuilder.append("----------")
        Logger.info(pricesStringBuilder.mkString)
    }
}