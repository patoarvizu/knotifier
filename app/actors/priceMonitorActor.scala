package actors

import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.ec2.model.SpotPrice
import model.SpotPriceInfo
import java.util.Date
import scala.collection.mutable.HashMap
import scala.collection.immutable.{HashMap => ImmutableHashMap}
import scala.collection.mutable.Map
import scala.collection.immutable.{Map => ImmutableMap}
import scala.concurrent.Future
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.model.InstanceType._
import model.SpotPriceInfo
import play.Logger
import util.WeightedPriceCalculator
import scala.collection.immutable.AbstractMap
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global

trait PriceMonitor extends Actor with AmazonClient {

    def getWeightedPrices: ImmutableMap[InstanceType, SpotPriceInfo]
    
    def monitorSpotPrices
}

class PriceMonitorImpl extends PriceMonitor {

    private final val lowestPrices: Map[InstanceType, SpotPrice] = new HashMap[InstanceType, SpotPrice]
    private final val lowestWeightedPrices: Map[InstanceType, SpotPriceInfo] = new HashMap[InstanceType, SpotPriceInfo]
    private final val instanceTypes: Set[InstanceType] = Set(C32xlarge, C34xlarge, C38xlarge, C3Large, C3Xlarge, M32xlarge, M3Large, M3Medium, M3Xlarge)
    private final val availabilityZones: Set[String] = Set("us-east-1a", "us-east-1d")
     private final val weightedPriceCalculator: WeightedPriceCalculator = new WeightedPriceCalculator
    
    def getWeightedPrices = lowestWeightedPrices.toMap
    
    def monitorSpotPrices = {
        for {
            instanceType <- instanceTypes
            availabilityZone <- availabilityZones
        } {
            Future { setWeightedPrice(instanceType, availabilityZone) }
        }

        printPrices
    }
    
    private[this] def setWeightedPrice(instanceType: InstanceType, availabilityZone: String) = {
        val weightedPrice: Double = weightedPriceCalculator.getWeightedPrice(instanceType, availabilityZone)
            lowestWeightedPrices.synchronized {
                    if(!lowestWeightedPrices.contains(instanceType))
                        lowestWeightedPrices(instanceType) = SpotPriceInfo(instanceType, availabilityZone, weightedPrice)
                    else if(lowestWeightedPrices(instanceType).price > weightedPrice)
                    {
                        val spotPriceInfo: SpotPriceInfo = lowestWeightedPrices(instanceType)
                        lowestWeightedPrices(instanceType) = spotPriceInfo.copy(availabilityZone=availabilityZone, price= weightedPrice)
                    }
                }
    }
    
    
    private[this] def printPrices =
    {
        Logger.debug(new Date().toString)
        lowestWeightedPrices.values foreach { spotPrice: SpotPriceInfo =>
            Logger.debug(" --- Price for instance type " + spotPrice.instanceType + " in availability zone " + spotPrice.availabilityZone + " is " + spotPrice.price)
        }
        Logger.debug("----------")
    }
}