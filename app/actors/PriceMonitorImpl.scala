package actors

import java.util.Date

import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import scala.concurrent.Future

import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.model.InstanceType._

import model.SpotPriceInfo
import play.Logger
import util.WeightedPriceCalculator

class PriceMonitorImpl extends PriceMonitor {

	private final val lowestPrices: Map[InstanceType, SpotPrice] = new HashMap[InstanceType, SpotPrice]();
    private final val lowestWeightedPrices: Map[InstanceType, SpotPriceInfo] = new HashMap[InstanceType, SpotPriceInfo]();
    private final val instanceTypes: List[InstanceType] = getSpotEligibleInstanceTypes();
    private final val availabilityZones: Array[String] = Array[String]("us-east-1a", "us-east-1d");
    private final val weightedPriceCalculator: WeightedPriceCalculator = new WeightedPriceCalculator();
    
	def getPrices(): Map[InstanceType, SpotPriceInfo] = {
		lowestWeightedPrices;
	}
    
    def monitorSpotPrices(): Unit = {
    	instanceTypes map
        { instanceType: InstanceType =>
            availabilityZones map
            { availabilityZone: String =>
            	Future {
                	val weightedPrice: Double = weightedPriceCalculator.getWeightedPrice(instanceType, availabilityZone);
                	lowestWeightedPrices.synchronized {
                    	    if(!lowestWeightedPrices.contains(instanceType))
                    	        lowestWeightedPrices += (instanceType -> new SpotPriceInfo(instanceType, availabilityZone, weightedPrice));
                    	    else if(lowestWeightedPrices(instanceType).price > weightedPrice)
                    	    {
                    	    	val spotPriceInfo: SpotPriceInfo = lowestWeightedPrices(instanceType)
                    	        spotPriceInfo.setSpotPrice(availabilityZone, weightedPrice);
                    	    	lowestWeightedPrices += (instanceType -> spotPriceInfo);
                    	    }
                	    }
            	}
            }
        }
    	printPrices
    }
    
    private[this] def getSpotEligibleInstanceTypes(): List[InstanceType] =
    {
        List(C32xlarge, C34xlarge, C38xlarge, C3Large, C3Xlarge, M32xlarge, M3Large, M3Medium, M3Xlarge);
    }
    
    private[this] def printPrices(): Unit =
    {
        Logger.debug(new Date().toString());
        Logger.debug("Unsorted prices:");
        lowestWeightedPrices.values map { spotPrice: SpotPriceInfo =>
            Logger.debug(" --- Price for instance type " + spotPrice.instanceType + " in availability zone " + spotPrice.availabilityZone + " is " + spotPrice.price);
        }
        Logger.debug("----------");
    }
}