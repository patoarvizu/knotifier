package actors

import java.util.ArrayList

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.mapAsJavaConcurrentMap
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.Map
import scala.collection.SortedMap
import scala.collection.mutable.HashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.Tag
import com.amazonaws.services.autoscaling.model.TagDescription
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.sqs.model.CreateQueueResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.amazonaws.util.json.JSONObject

import actors.AutoScalingDataMonitor.autoScalingGroups
import actors.AutoScalingDataMonitor.launchConfigurations
import actors.AutoScalingDataMonitor.updateAutoScalingGroupsData
import actors.AutoScalingDataMonitor.updateLaunchConfigurationsData
import akka.util.Timeout
import model.ReplacementInfo
import model.SpotPriceInfo
import play.Logger
import util.AmazonClient

object AutoScaleModifier extends AmazonClient {

    private final val SpotGroupType: String = "Spot"
    private final val NotificationTypeField: String = "Event"
    private final val MessageField: String = "Message"
    private final val AutoScalingGroupNameField: String = "AutoScalingGroupName"
    private final val AutoScalingInstanceTerminateMessage: String = "autoscaling:EC2_INSTANCE_TERMINATE"
    private final val SpotPriceTag: String = "SpotPrice"
    private final val GroupTypeTag: String = "GroupType"
    private final val PreferredTypesTag: String = "PreferredTypes"
    private final val BaseSpotGroupNameTag: String = "BaseSpotGroupName"
    private final val KnotifierQueueName: String = "knotifier-queue"

    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = HashMap[String, ReplacementInfo]()

    def monitorAutoScaleGroups = {

        if(autoScalingGroups.isEmpty || launchConfigurations.isEmpty)
            Logger.debug("AutoScaling data is not ready yet")
        else {
            val queueResult: CreateQueueResult = sqsClient.createQueue(KnotifierQueueName)
            val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl).withMaxNumberOfMessages(100))
            sqsMessages.getMessages foreach {sqsMessage: Message => processSQSMessage(sqsMessage)}
            spotReplacementInfoByGroup.keySet foreach { group: String => processReplacementInfo(group) }
            sqsMessages.getMessages foreach { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl, sqsMessage.getReceiptHandle)}
            spotReplacementInfoByGroup.clear
        }
    }

    private[this] def processSQSMessage(sqsMessage: Message) = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody)
        val notification: JSONObject = new JSONObject(message.getString(MessageField))
        val autoScalingGroupName: String = notification.getString(AutoScalingGroupNameField)
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(autoScalingGroupName, throw new RuntimeException(s"Auto-scaling group $autoScalingGroupName doesn't exist"))
        val tags: Map[String, String] = getTagMap(autoScalingGroup.getTags)
        val baseSpotGroupName: String = tags.getOrElse(BaseSpotGroupNameTag, throw new RuntimeException(s"$BaseSpotGroupNameTag tag is missing"))
        if(AutoScalingInstanceTerminateMessage == notification.getString(NotificationTypeField))
        {
            if(tags.get(GroupTypeTag) == Some(SpotGroupType)) {
                spotReplacementInfoByGroup.get(autoScalingGroupName) match {
                    case Some(spotReplacementInfo) => {
                        spotReplacementInfoByGroup.put(autoScalingGroupName, spotReplacementInfo.copy(newInstances=spotReplacementInfo.getInstanceCount + 1))
                    }
                    case None =>
                        spotReplacementInfoByGroup.put(autoScalingGroupName, 
                            ReplacementInfo(baseSpotGroupName=baseSpotGroupName, originalCapacity=autoScalingGroup.getDesiredCapacity(), tags=tags))
                }
            }
        }
    }

    private[this] def processReplacementInfo(spotGroupName: String) = {
        val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.getOrElse(spotGroupName,
                throw new RuntimeException("Replacement info object wasn't found"))
        Logger.debug(s"Replacements needed for group $spotGroupName: ${replacementInfo.getInstanceCount}")
        val baseSpotGroupName: String = replacementInfo.baseSpotGroupName
        val newInstanceInfo: SpotPriceInfo = discoverNewInstanceInfo(replacementInfo.getTagValue(PreferredTypesTag))
        
        if(!launchConfigurations.containsKey(s"$baseSpotGroupName-${newInstanceInfo.instanceType}"))
        {
            val launchConfiguration: LaunchConfiguration = launchConfigurations.getOrElse(baseSpotGroupName,
                    throw new RuntimeException(s"Launch configuration $baseSpotGroupName wasn't found"))
            val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(newInstanceInfo.instanceType.toString, replacementInfo.tags.getOrElse(SpotPriceTag, ""), launchConfiguration)
            asClient.createLaunchConfiguration(createLaunchConfigurationRequest)
            updateLaunchConfigurationsData
        }
        if(autoScalingGroups.containsKey(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}"))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(spotGroupName,
                    throw new Exception(s"Auto scaling group $spotGroupName wasn't found"))
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(s"$baseSpotGroupName-${newInstanceInfo.availabilityZone}")
            updateAutoScalingGroupRequest.setLaunchConfigurationName(s"${replacementInfo.baseSpotGroupName}-${newInstanceInfo.instanceType}")
            updateAutoScalingGroupRequest.setDesiredCapacity(autoScalingGroup.getDesiredCapacity() + replacementInfo.getInstanceCount)
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
        }
        else
            throw new RuntimeException(s"Auto scaling group $baseSpotGroupName-${newInstanceInfo.availabilityZone} wasn't found")
        if(autoScalingGroups.containsKey(spotGroupName))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(spotGroupName,
                    throw new Exception(s"Auto scaling group $spotGroupName wasn't found"))
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(spotGroupName)
            updateAutoScalingGroupRequest.setDesiredCapacity(autoScalingGroup.getDesiredCapacity() - replacementInfo.getInstanceCount)
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
        }
        else
            throw new RuntimeException(s"Auto scaling group $spotGroupName wasn't found")
        updateAutoScalingGroupsData
    }

    private[this] def getTagMap(tagDescriptions: Iterable[TagDescription]): Map[String, String] = {
        tagDescriptions map { tagDescription => tagDescription.getKey() -> tagDescription.getValue()} toMap
    }

    private[this] def discoverNewInstanceInfo(preferredTypes: String): SpotPriceInfo =
    {
        val preferredTypesSet: Set[String] = preferredTypes.split(",").toSet
        val weightedPrices: Map[InstanceType, SpotPriceInfo] = PriceMonitor.getWeightedPrices
        SortedMap[Double, SpotPriceInfo](weightedPrices.filterKeys({instanceType: InstanceType =>
        preferredTypesSet.contains(instanceType.toString)
        }).collect({
            case (instanceType: InstanceType, spotPriceInfo: SpotPriceInfo) => spotPriceInfo.price -> spotPriceInfo
        }).toSeq:_*).head._2
    }

    private[this] def composeNewLaunchConfigurationRequest(instanceType: String, spotPrice: String, launchConfiguration: LaunchConfiguration): CreateLaunchConfigurationRequest =
    {
        val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = new CreateLaunchConfigurationRequest
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId)
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName)
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups)
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData)
        createLaunchConfigurationRequest.setInstanceType(instanceType)
        createLaunchConfigurationRequest.setSpotPrice(spotPrice)
        createLaunchConfigurationRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName, instanceType))
        createLaunchConfigurationRequest
    }

    private[this] def newInstanceTypeLaunchConfigurationName(baseLaunchConfigurationName: String, instanceType: String): String =
    {
        return s"$baseLaunchConfigurationName-$instanceType"
    }
}