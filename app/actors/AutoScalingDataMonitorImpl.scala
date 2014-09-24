package actors

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import scala.concurrent.Future

import com.amazonaws.services.autoscaling.model._


class AutoScalingDataMonitorImpl extends AutoScalingDataMonitor {
	
	private final val autoScalingGroups: Map[String, AutoScalingGroup] = new HashMap[String, AutoScalingGroup]();
    private final val launchConfigurations: Map[String, LaunchConfiguration] = new HashMap[String, LaunchConfiguration]();
    
	def monitorAutoScalingData(): Unit = {
		val autoScalingGroupsFuture: Future[DescribeAutoScalingGroupsResult] = Future { asClient.describeAutoScalingGroups() }
		autoScalingGroupsFuture onSuccess {
			case result: DescribeAutoScalingGroupsResult => {
				autoScalingGroups.synchronized {
					result.getAutoScalingGroups() map {autoScalingGroup => autoScalingGroups.put(autoScalingGroup.getAutoScalingGroupName(), autoScalingGroup);};
				}
			}
		};
		val launchConfigurationsFuture: Future[DescribeLaunchConfigurationsResult] = Future { asClient.describeLaunchConfigurations() }
		launchConfigurationsFuture onSuccess {
		    case result: DescribeLaunchConfigurationsResult => {
		        launchConfigurations.synchronized {
		        	result.getLaunchConfigurations() map {launchConfiguration => launchConfigurations.put(launchConfiguration.getLaunchConfigurationName(), launchConfiguration);};
		        }
		    }
		};
	}
    
    def updateAutoScalingGroupsData(): Unit = {
    	val autoScalingGroupsResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups();
    	autoScalingGroupsResult.getAutoScalingGroups() map {autoScalingGroup => autoScalingGroups.put(autoScalingGroup.getAutoScalingGroupName(), autoScalingGroup);};
    }
    
    def updateLaunchConfigurationsData(): Unit = {
    	val launchConfigurationsResult: DescribeLaunchConfigurationsResult = asClient.describeLaunchConfigurations();
    	launchConfigurationsResult.getLaunchConfigurations() map {launchConfiguration => launchConfigurations.put(launchConfiguration.getLaunchConfigurationName(), launchConfiguration);};
    }
    
    def getAutoScaleData(): Map[String, AutoScalingGroup] = { autoScalingGroups }
    
    def getLaunchConfigurationData(): Map[String, LaunchConfiguration] = { launchConfigurations }
}