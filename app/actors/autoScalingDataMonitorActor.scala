package actors

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import scala.collection.JavaConversions._
import scala.collection.immutable.Map
import scala.collection.concurrent.{Map => ConcurrentMap}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global
import play.Logger

object AutoScalingDataMonitor extends AmazonClient {
    
    val autoScalingGroups: ConcurrentMap[String, AutoScalingGroup] = new TrieMap[String, AutoScalingGroup];
    
    val launchConfigurations: ConcurrentMap[String, LaunchConfiguration] = new TrieMap[String, LaunchConfiguration]

    def monitorAutoScalingData = {
        val autoScalingGroupsFuture: Future[DescribeAutoScalingGroupsResult] = Future { asClient.describeAutoScalingGroups }
        autoScalingGroupsFuture onSuccess {
            case result: DescribeAutoScalingGroupsResult => {
                result.getAutoScalingGroups foreach {autoScalingGroup => autoScalingGroups(autoScalingGroup.getAutoScalingGroupName()) = autoScalingGroup}
            }
        }
        val launchConfigurationsFuture: Future[DescribeLaunchConfigurationsResult] = Future { asClient.describeLaunchConfigurations }
        launchConfigurationsFuture onSuccess {
            case result: DescribeLaunchConfigurationsResult => {
                result.getLaunchConfigurations foreach {launchConfiguration => launchConfigurations(launchConfiguration.getLaunchConfigurationName) = launchConfiguration}
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
    
}
