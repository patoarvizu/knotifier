package actors

import scala.collection.Map
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.ec2.model.SpotPrice
import model.SpotPriceInfo

trait PriceMonitor extends AmazonClientActor {

	def getPrices(): Map[InstanceType, SpotPriceInfo];
    
    def monitorSpotPrices(): Unit;
}