package actors

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap
import scala.collection.mutable.Map
import scala.collection.immutable.{HashMap => ImmutableHashMap}
import scala.collection.immutable.{Map => ImmutableMap}
import scala.concurrent.Future
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global

trait AutoScalingDataMonitor extends Actor with AmazonClient {
    
    def monitorAutoScalingData
    
    def updateAutoScalingGroupsData
    
    def updateLaunchConfigurationsData
    
    def getAutoScaleData: ImmutableMap[String, AutoScalingGroup]
    
    def getLaunchConfigurationData: ImmutableMap[String, LaunchConfiguration]
}

class AutoScalingDataMonitorImpl extends AutoScalingDataMonitor {
    
    private final val autoScalingGroups: Map[String, AutoScalingGroup] = new HashMap[String, AutoScalingGroup]
    private final val launchConfigurations: Map[String, LaunchConfiguration] = new HashMap[String, LaunchConfiguration]
    
    def monitorAutoScalingData = {
        val autoScalingGroupsFuture: Future[DescribeAutoScalingGroupsResult] = Future { asClient.describeAutoScalingGroups }
        autoScalingGroupsFuture onSuccess {
            case result: DescribeAutoScalingGroupsResult => {
                autoScalingGroups.synchronized {
                    result.getAutoScalingGroups foreach {autoScalingGroup => autoScalingGroups(autoScalingGroup.getAutoScalingGroupName()) = autoScalingGroup}
                }
            }
        }
        val launchConfigurationsFuture: Future[DescribeLaunchConfigurationsResult] = Future { asClient.describeLaunchConfigurations }
        launchConfigurationsFuture onSuccess {
            case result: DescribeLaunchConfigurationsResult => {
                launchConfigurations.synchronized {
                    result.getLaunchConfigurations foreach {launchConfiguration => launchConfigurations(launchConfiguration.getLaunchConfigurationName) = launchConfiguration}
                }
            }
        }
    }

    
    def updateAutoScalingGroupsData = {
        val autoScalingGroupsResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups
        autoScalingGroupsResult.getAutoScalingGroups map {autoScalingGroup => autoScalingGroups(autoScalingGroup.getAutoScalingGroupName) = autoScalingGroup}
    }
    
    def updateLaunchConfigurationsData() = {
        val launchConfigurationsResult: DescribeLaunchConfigurationsResult = asClient.describeLaunchConfigurations()
        launchConfigurationsResult.getLaunchConfigurations() map {launchConfiguration => launchConfigurations(launchConfiguration.getLaunchConfigurationName()) = launchConfiguration}
    }
    
    def getAutoScaleData: ImmutableMap[String, AutoScalingGroup] = autoScalingGroups.toMap
    
    def getLaunchConfigurationData: ImmutableMap[String, LaunchConfiguration] = launchConfigurations.toMap
}