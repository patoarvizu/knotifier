package actors

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import scala.collection.JavaConversions._
import scala.collection.concurrent.Map
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import util.AmazonClient
import scala.concurrent.ExecutionContext.Implicits.global
import play.Logger
import com.amazonaws.services.autoscaling.model.TagDescription
import scala.util.matching.Regex
import scala.util.matching.Regex.Match
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest

object AutoScalingDataMonitor extends AmazonClient {

    private final val SystemTag: String = "System";
    final val StackNameTag: String = "aws:cloudformation:stack-name"
    private final val AvailabilityZone: String = "AvailabilityZone"
    private final val AutoScaleGroupSuffix: String = "ASScalingGroupSpot"

    val autoScalingGroups: Map[String, AutoScalingGroup] = new TrieMap[String, AutoScalingGroup];
    val launchConfigurations: Map[String, LaunchConfiguration] = new TrieMap[String, LaunchConfiguration]

    def monitorAutoScalingData = {
        val autoScalingGroupsFuture: Future[DescribeAutoScalingGroupsResult] = Future { asClient.describeAutoScalingGroups }
        autoScalingGroupsFuture onSuccess {
            case result: DescribeAutoScalingGroupsResult => {
                result.getAutoScalingGroups foreach putAutoScalingGroupInMap
            }
        }
        val launchConfigurationsFuture: Future[DescribeLaunchConfigurationsResult] = Future { asClient.describeLaunchConfigurations }
        launchConfigurationsFuture onSuccess {
            case result: DescribeLaunchConfigurationsResult => {
                result.getLaunchConfigurations foreach putLaunchConfigurationInMap
            }
        }
    }

    def updateSingleAutoScalingGroup(autoScalingGroupName: String) = {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups(autoScalingGroupName)
        val autoScalingGroupResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(autoScalingGroup.getAutoScalingGroupName))
        if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
            putAutoScalingGroupInMap(autoScalingGroupResult.getAutoScalingGroups.get(0))
    }

    def updateSingleLaunchConfiguration(launchConfigurationName: String) = {
        val launchConfiguration: LaunchConfiguration = launchConfigurations(launchConfigurationName)
        Future { asClient.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest withLaunchConfigurationNames(launchConfiguration.getLaunchConfigurationName)) } onSuccess {
            case launchConfigurationResult: DescribeLaunchConfigurationsResult =>
                if(!launchConfigurationResult.getLaunchConfigurations.isEmpty)
                    putLaunchConfigurationInMap(launchConfigurationResult.getLaunchConfigurations.get(0))
        }
    }

    def updateAutoScalingGroupsData = {
        val autoScalingGroupsResult: DescribeAutoScalingGroupsResult = asClient.describeAutoScalingGroups
        autoScalingGroupsResult.getAutoScalingGroups foreach putAutoScalingGroupInMap
    }

    def updateLaunchConfigurationsData() = {
        val launchConfigurationsResult: DescribeLaunchConfigurationsResult = asClient.describeLaunchConfigurations()
        launchConfigurationsResult.getLaunchConfigurations foreach putLaunchConfigurationInMap
    }

    def getAutoScalingGroupsMapIndex(autoScalingGroup: AutoScalingGroup): String = {
        val tags: Iterable[TagDescription] = autoScalingGroup.getTags
                val stackName: String = getTagValue(tags, StackNameTag)
                val system: String = getTagValue(tags, SystemTag)
                val availabilityZone: String = getTagValue(tags, AvailabilityZone)
                if(stackName.isEmpty || system.isEmpty || availabilityZone.isEmpty)
                    stripARNid(autoScalingGroup.getAutoScalingGroupName)
                    else
                        s"$stackName-$system$AutoScaleGroupSuffix-$availabilityZone"
    }

    private[this] def getLaunchConfigurationsMapIndex(launchConfiguration: LaunchConfiguration): String = {
        stripARNid(launchConfiguration.getLaunchConfigurationName);
    }

    private[this] def putAutoScalingGroupInMap(autoScalingGroup: AutoScalingGroup) = {
        val mapIndex = getAutoScalingGroupsMapIndex(autoScalingGroup)
        autoScalingGroups(mapIndex) = autoScalingGroup
    }

    private[this] def putLaunchConfigurationInMap(launchConfiguration: LaunchConfiguration) = {
        val mapIndex = getLaunchConfigurationsMapIndex(launchConfiguration);
        launchConfigurations(mapIndex) = launchConfiguration
    }

    def getAutoScalingGroupByAWSName(awsName: String): AutoScalingGroup = {
        autoScalingGroups.values.find({ autoScalingGroup: AutoScalingGroup => autoScalingGroup.getAutoScalingGroupName == awsName}).getOrElse({
            val autoScalingGroupResult = asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest withAutoScalingGroupNames(awsName))
            if(!autoScalingGroupResult.getAutoScalingGroups.isEmpty)
            {
                val autoScalingGroup = autoScalingGroupResult.getAutoScalingGroups.get(0)
                putAutoScalingGroupInMap(autoScalingGroup)
                autoScalingGroup
            }
            else
                throw new RuntimeException(s"Auto scaling group $awsName could not be found")
        })
    }

    private[this] def getTagValue(tags: Iterable[TagDescription], tagKey: String): String = {
        val tagDescription: Option[TagDescription] = (tags find({tagDescription: TagDescription => tagDescription.getKey == tagKey }))
        tagDescription match {
            case Some(tagDescription) => tagDescription.getValue()
            case None => ""
        }
    }

    private[this] def stripARNid(name: String): String = {
        val idRegex = new Regex("""(.*)(-[0-9A-Z]{12,13})$""", "name", "ARNid")
        idRegex findFirstMatchIn name match {
            case Some(matched) => matched.group("name")
            case None => name
        }
    }
}