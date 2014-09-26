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
    private final val OnDemandGroupType: String = "OnDemand"
    private final val NotificationTypeField: String = "Event"
    private final val MessageField: String = "Message"
    private final val AutoScalingGroupNameField: String = "AutoScalingGroupName"
    private final val AutoScalingInstanceTerminateMessage: String = "autoscaling:EC2_INSTANCE_TERMINATE"
    private final val SpotPriceTag: String = "SpotPrice"
    private final val GroupTypeTag: String = "GroupType"
    private final val NameTag: String = "Name"
    private final val PreferredTypesTag: String = "PreferredTypes"
    private final val SpotGroupNameSuffix: String = "-spot"
    private final val KnotifierQueueName: String = "knotifier-queue"
    private final val TimeoutDuration: FiniteDuration = 5.seconds
    private implicit val timeout: Timeout = new Timeout(TimeoutDuration)
    
    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = HashMap[String, ReplacementInfo]()
    
    def monitorAutoScaleGroups = {
        
        if(autoScalingGroups.isEmpty || launchConfigurations.isEmpty)
            Logger.debug("AutoScaling data is not ready yet")
        else {
            val queueResult: CreateQueueResult = sqsClient.createQueue(KnotifierQueueName)
            val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl))
            sqsMessages.getMessages foreach {sqsMessage: Message => processSQSMessage(sqsMessage)}
            spotReplacementInfoByGroup.keySet foreach { group: String => processReplacementInfo(group) }
            sqsMessages.getMessages foreach { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl, sqsMessage.getReceiptHandle)}
            updateAutoScalingGroupsData
            spotReplacementInfoByGroup.clear
        }
    }

    private[this] def processSQSMessage(sqsMessage: Message) = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody)
        val notification: JSONObject = new JSONObject(message.getString(MessageField))
        val autoScalingGroupName: String = notification.getString(AutoScalingGroupNameField)
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(autoScalingGroupName, throw new RuntimeException(s"Auto-scaling group $autoScalingGroupName doesn't exist"))
        val tags: Map[String, String] = getTagMap(autoScalingGroup.getTags)
        if(AutoScalingInstanceTerminateMessage == notification.getString(NotificationTypeField))
        {
            if(tags.get(GroupTypeTag) == Some(OnDemandGroupType)) {
                spotReplacementInfoByGroup.get(autoScalingGroupName) match {
                    case Some(spotReplacementInfo) => {
                        spotReplacementInfoByGroup.put(autoScalingGroupName, spotReplacementInfo.increaseInstanceCount)
                    }
                    case None =>  
                        spotReplacementInfoByGroup.put(autoScalingGroupName, 
                            ReplacementInfo(launchConfigurationName=autoScalingGroup.getLaunchConfigurationName, originalCapacity=autoScalingGroup.getDesiredCapacity(), tags=tags))
                }
            }
        }
    }

    private[this] def processReplacementInfo(group: String) = {
        val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.getOrElse(group,
                throw new RuntimeException("Replacement info object doesn't exist"))
        Logger.debug(s"Replacements needed for group $group: ${replacementInfo.getInstanceCount}")
        Logger.debug(s"Original capacity for group $group: ${replacementInfo.originalCapacity}")
        val newInstanceType: String = discoverNewInstanceType(replacementInfo.getTagValue(PreferredTypesTag))
        
        if(!launchConfigurations.containsKey(s"${replacementInfo.launchConfigurationName}-$newInstanceType"))
        {
            implicit val launchConfiguration: LaunchConfiguration = launchConfigurations.getOrElse(replacementInfo.launchConfigurationName,
                    throw new Exception(s"Launch configuration ${replacementInfo.launchConfigurationName} doesn't exist"))
            val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(newInstanceType, replacementInfo.tags.getOrElse(SpotPriceTag, ""))
            asClient.createLaunchConfiguration(createLaunchConfigurationRequest)
            updateLaunchConfigurationsData
        }
        if(autoScalingGroups.containsKey(group + SpotGroupNameSuffix))
        {
            val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(group + SpotGroupNameSuffix,
                    throw new Exception(s"Auto scaling group $group$SpotGroupNameSuffix doesn't exist"))
            val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest
            updateAutoScalingGroupRequest.setAutoScalingGroupName(group + SpotGroupNameSuffix)
            updateAutoScalingGroupRequest.setLaunchConfigurationName(s"${replacementInfo.launchConfigurationName}-$newInstanceType")
            updateAutoScalingGroupRequest.setDesiredCapacity(autoScalingGroup.getDesiredCapacity() + replacementInfo.getInstanceCount)
            asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest)
        }
        else
            asClient.createAutoScalingGroup(composeNewAutoScalingGroupRequest(group, newInstanceType, replacementInfo, autoScalingGroups))
    }

    private[this] def getTagMap(tagDescriptions: Iterable[TagDescription]): Map[String, String] = {
        tagDescriptions map { tagDescription => tagDescription.getKey() -> tagDescription.getValue()} toMap
    }
    
    private[this] def discoverNewInstanceType(preferredTypes: String): String =
    {
        val preferredTypesSet: Set[String] = preferredTypes.split(",").toSet
        val weightedPrices: Map[InstanceType, SpotPriceInfo] = PriceMonitor.getWeightedPrices
        SortedMap[Double, InstanceType](weightedPrices.filterKeys({instanceType: InstanceType =>
        preferredTypesSet.contains(instanceType.toString)
        }).collect({
            case (instanceType: InstanceType, spotPriceInfo: SpotPriceInfo) => spotPriceInfo.price -> instanceType
        }).toSeq:_*).head._2.toString
    }
    
    private[this] def composeNewAutoScalingGroupRequest(group: String, newInstanceType: String, replacementInfo: ReplacementInfo, autoScalingGroups: Map[String, AutoScalingGroup]): CreateAutoScalingGroupRequest =
    {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(group,
                throw new RuntimeException(s"Auto scaling group $group was not found"))
        val createAutoScalingGroupRequest: CreateAutoScalingGroupRequest = new CreateAutoScalingGroupRequest
        createAutoScalingGroupRequest.setAutoScalingGroupName(group + SpotGroupNameSuffix)
        createAutoScalingGroupRequest.setAvailabilityZones(autoScalingGroup.getAvailabilityZones)
        createAutoScalingGroupRequest.setDefaultCooldown(0)
        createAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.getInstanceCount)
        createAutoScalingGroupRequest.setHealthCheckGracePeriod(autoScalingGroup.getHealthCheckGracePeriod)
        createAutoScalingGroupRequest.setHealthCheckType(autoScalingGroup.getHealthCheckType)
        createAutoScalingGroupRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(replacementInfo.launchConfigurationName, newInstanceType))
        createAutoScalingGroupRequest.setLoadBalancerNames(autoScalingGroup.getLoadBalancerNames)
        createAutoScalingGroupRequest.setMaxSize(autoScalingGroup.getMaxSize)
        createAutoScalingGroupRequest.setMinSize(autoScalingGroup.getMinSize)
        val tags: ArrayList[Tag] = new ArrayList[Tag]
        tags.add(new Tag().withKey(GroupTypeTag).withValue(SpotGroupType))
        tags.add(new Tag().withKey(NameTag).withValue(createAutoScalingGroupRequest.getAutoScalingGroupName))
        createAutoScalingGroupRequest.setTags(tags)
        createAutoScalingGroupRequest
    }
    
    private[this] def composeNewLaunchConfigurationRequest(instanceType: String, spotPrice: String)(implicit launchConfiguration: LaunchConfiguration): CreateLaunchConfigurationRequest =
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
    
    private[this] def newInstanceTypeLaunchConfigurationName(launchConfigurationName: String, instanceType: String): String =
    {
        return s"$launchConfigurationName-$instanceType"
    }
}