package actors

import scala.collection.Map
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration

trait AutoScalingDataMonitor extends AmazonClientActor {
	
    def monitorAutoScalingData(): Unit;
    
    def updateAutoScalingGroupsData(): Unit;
    
    def updateLaunchConfigurationsData(): Unit;
    
    def getAutoScaleData(): Map[String, AutoScalingGroup];
    
    def getLaunchConfigurationData(): Map[String, LaunchConfiguration];
}