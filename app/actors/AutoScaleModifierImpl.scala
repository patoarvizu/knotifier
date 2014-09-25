package actors

import java.util.ArrayList
import java.util.Map.Entry
import scala.collection.JavaConversions._
import scala.collection.Map
import scala.collection.SortedMap
import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import com.amazonaws.services.autoscaling.model._
import com.amazonaws.services.ec2.model.InstanceType
import com.amazonaws.services.sqs.model._
import com.amazonaws.util.json.JSONObject
import akka.actor.ActorRef
import akka.actor.TypedActor
import akka.actor.TypedProps
import akka.util.Timeout
import model.ReplacementInfo
import model.SpotPriceInfo
import play.Logger
import play.libs.Akka
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

class AutoScaleModifierImpl extends AutoScaleModifier {
    
	private final val SPOT_GROUP_TYPE: String = "Spot";
    private final val ON_DEMAND_GROUP_TYPE: String = "OnDemand";
    private final val NOTIFICATION_TYPE_FIELD: String = "Event";
    private final val MESSAGE_FIELD: String = "Message";
    private final val AUTO_SCALING_GROUP_NAME_FIELD: String = "AutoScalingGroupName";
    private final val AUTOSCALING_INSTANCE_TERMINATE_MESSAGE: String = "autoscaling:EC2_INSTANCE_TERMINATE";
    private final val SPOT_PRICE_TAG: String = "SpotPrice";
    private final val GROUP_TYPE_TAG: String = "GroupType";
    private final val NAME_TAG: String = "Name";
    private final val PREFERRED_TYPES_TAG: String = "PreferredTypes";
    private final val SPOT_GROUP_NAME_SUFFIX: String = "-spot";
    private final val KNOTIFIER_QUEUE_NAME: String = "knotifier-queue"
    private final val TIMEOUT_DURATION: FiniteDuration = 5.seconds;
    private implicit val timeout: Timeout = new Timeout(TIMEOUT_DURATION)
    private val spotReplacementInfoByGroup: HashMap[String, ReplacementInfo] = new HashMap[String, ReplacementInfo]();
    
	val priceMonitor: PriceMonitor = {
    	val priceMonitorActorRefFuture: Future[ActorRef] = Akka.system.actorSelection("akka://application/user/priceMonitor").resolveOne;
        priceMonitorActorRefFuture.onComplete({
            case Failure(e) => throw e;
            case Success(priceMonitorActorRef) => priceMonitorActorRef;
        })
        TypedActor.get(typedActorContext).typedActorOf(new TypedProps[PriceMonitorImpl](classOf[PriceMonitor], classOf[PriceMonitorImpl]), Await.result(priceMonitorActorRefFuture, TIMEOUT_DURATION));
    };
    
    val autoScalingDataMonitor: AutoScalingDataMonitor = {
        val autoScalingDataMonitorFuture: Future[ActorRef] = Akka.system.actorSelection("akka://application/user/autoScalingDataMonitor").resolveOne;
        autoScalingDataMonitorFuture.onComplete({
            case Failure(e) => throw e;
            case Success(autoScalingDataMonitorRef) => autoScalingDataMonitorRef;
        });
        TypedActor.get(typedActorContext).typedActorOf(new TypedProps[AutoScalingDataMonitorImpl](classOf[AutoScalingDataMonitor], classOf[AutoScalingDataMonitorImpl]), Await.result(autoScalingDataMonitorFuture, TIMEOUT_DURATION));
    };
	
	def monitorAutoScaleGroups(): Unit = {
		implicit val autoScalingGroups: Map[String, AutoScalingGroup] = autoScalingDataMonitor.getAutoScaleData();
		implicit val launchConfigurations: Map[String, LaunchConfiguration] = autoScalingDataMonitor.getLaunchConfigurationData();
		if(autoScalingGroups.isEmpty || launchConfigurations.isEmpty)
		{
			Logger.debug("AutoScaling data is not ready yet");
			return;
		}
		val queueResult: CreateQueueResult = sqsClient.createQueue(KNOTIFIER_QUEUE_NAME);
		val sqsMessages: ReceiveMessageResult = sqsClient.receiveMessage(new ReceiveMessageRequest().withQueueUrl(queueResult.getQueueUrl()));
		sqsMessages.getMessages() map {sqsMessage => processSQSMessage(sqsMessage)};
		spotReplacementInfoByGroup.keySet map { group => processReplacementInfo(group) }
		sqsMessages.getMessages() map { sqsMessage => sqsClient.deleteMessage(queueResult.getQueueUrl(), sqsMessage.getReceiptHandle());};
		autoScalingDataMonitor.updateAutoScalingGroupsData();
        spotReplacementInfoByGroup.clear();
	};

	private[this] def processSQSMessage(sqsMessage: Message)(implicit autoScalingGroups: Map[String, AutoScalingGroup]): Unit = {
        val message: JSONObject = new JSONObject(sqsMessage.getBody());
        val notification: JSONObject = new JSONObject(message.getString(MESSAGE_FIELD));
        val autoScalingGroupName: String = notification.getString(AUTO_SCALING_GROUP_NAME_FIELD);
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(autoScalingGroupName, throw new Exception("Auto-scaling group " + autoScalingGroupName + " doesn't exist"));
        val tags: Map[String, String] = getTagMap(autoScalingGroup.getTags().toList);
        tags.keys foreach { tagKey => Logger.debug(tagKey); };
        val groupType: String = tags.getOrElse(GROUP_TYPE_TAG, null);
        if(AUTOSCALING_INSTANCE_TERMINATE_MESSAGE equals(notification.getString(NOTIFICATION_TYPE_FIELD)))
        {
            if(ON_DEMAND_GROUP_TYPE equals(groupType))
                if(spotReplacementInfoByGroup.containsKey(autoScalingGroupName)) spotReplacementInfoByGroup.get(autoScalingGroupName).get.increaseInstanceCount;
                else spotReplacementInfoByGroup.put(autoScalingGroupName, new ReplacementInfo(autoScalingGroup.getLaunchConfigurationName(), autoScalingGroup.getDesiredCapacity(), tags));
        }
    }

	private[this] def processReplacementInfo(group: String)(implicit autoScalingGroups: Map[String, AutoScalingGroup], launchConfigurations: Map[String, LaunchConfiguration]): Unit = {
		val replacementInfo: ReplacementInfo = spotReplacementInfoByGroup.getOrElse(group, throw new Exception("Replacement info object doesn't exist"));
        Logger.debug("Replacements needed for group " + group + ": " + replacementInfo.newInstances);
        Logger.debug("Original capacity for group " + group + ": " + replacementInfo.originalCapacity);
        val newInstanceType: String = discoverNewInstanceType(replacementInfo.getTagValue(PREFERRED_TYPES_TAG));
        launchConfigurations synchronized {
            if(!launchConfigurations.containsKey(replacementInfo.launchConfigurationName + "-" + newInstanceType))
            {
                implicit val launchConfiguration: LaunchConfiguration = launchConfigurations.getOrElse(replacementInfo.launchConfigurationName,
                        throw new Exception("Launch configuration " + replacementInfo.launchConfigurationName + " doesn't exist"));
                val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = composeNewLaunchConfigurationRequest(newInstanceType, replacementInfo.tags.getOrElse(SPOT_PRICE_TAG, ""));
                asClient.createLaunchConfiguration(createLaunchConfigurationRequest);
                autoScalingDataMonitor.updateLaunchConfigurationsData();
            }
        }
        autoScalingGroups synchronized
        {
            if(autoScalingGroups.containsKey(group + SPOT_GROUP_NAME_SUFFIX))
            {
                val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(group + SPOT_GROUP_NAME_SUFFIX,
                        throw new Exception("Auto scaling group " + group + SPOT_GROUP_NAME_SUFFIX + " doesn't exist"));
                val updateAutoScalingGroupRequest: UpdateAutoScalingGroupRequest = new UpdateAutoScalingGroupRequest();
                updateAutoScalingGroupRequest.setAutoScalingGroupName(group + SPOT_GROUP_NAME_SUFFIX)
                updateAutoScalingGroupRequest.setLaunchConfigurationName(replacementInfo.launchConfigurationName + "-" + newInstanceType);
                updateAutoScalingGroupRequest.setDesiredCapacity(autoScalingGroup.getDesiredCapacity() + replacementInfo.newInstances);
                asClient.updateAutoScalingGroup(updateAutoScalingGroupRequest);
            }
            else
                asClient.createAutoScalingGroup(composeNewAutoScalingGroupRequest(group, newInstanceType, replacementInfo, autoScalingGroups));
        }
	}

	private[this] def getTagMap(tagDescriptions: List[TagDescription]): Map[String, String] = {
		tagDescriptions map { tagDescription: TagDescription => tagDescription.getKey() -> tagDescription.getValue()} toMap
	}
	
	private[this] def discoverNewInstanceType(preferredTypes: String): String =
    {
        val preferredTypesList: List[String] = preferredTypes.split(",").toList;
        if(priceMonitor != null)
        {
            val sortedPrices: SortedMap[String, InstanceType] = SortedMap(priceMonitor.getPrices().entrySet().collect({
                case spotInstancePriceEntry: Entry[InstanceType, SpotPriceInfo]
                    if (preferredTypesList.contains(spotInstancePriceEntry.getKey().toString())) =>
                        (spotInstancePriceEntry.getValue().instanceType.toString() -> spotInstancePriceEntry.getKey());
            }).toSeq:_*);
            return sortedPrices.head.toString();
        }
        throw new Exception("Couldn't determine new instance type");
    }
	
	private[this] def composeNewAutoScalingGroupRequest(group: String, newInstanceType: String, replacementInfo: ReplacementInfo, autoScalingGroups: Map[String, AutoScalingGroup]): CreateAutoScalingGroupRequest =
    {
        val autoScalingGroup: AutoScalingGroup = autoScalingGroups.getOrElse(group, null);
        val createAutoScalingGroupRequest: CreateAutoScalingGroupRequest = new CreateAutoScalingGroupRequest();
        createAutoScalingGroupRequest.setAutoScalingGroupName(group + SPOT_GROUP_NAME_SUFFIX);
        createAutoScalingGroupRequest.setAvailabilityZones(autoScalingGroup.getAvailabilityZones());
        createAutoScalingGroupRequest.setDefaultCooldown(0);
        createAutoScalingGroupRequest.setDesiredCapacity(replacementInfo.newInstances);
        createAutoScalingGroupRequest.setHealthCheckGracePeriod(autoScalingGroup.getHealthCheckGracePeriod());
        createAutoScalingGroupRequest.setHealthCheckType(autoScalingGroup.getHealthCheckType());
        createAutoScalingGroupRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(replacementInfo.launchConfigurationName, newInstanceType));
        createAutoScalingGroupRequest.setLoadBalancerNames(autoScalingGroup.getLoadBalancerNames());
        createAutoScalingGroupRequest.setMaxSize(autoScalingGroup.getMaxSize());
        createAutoScalingGroupRequest.setMinSize(autoScalingGroup.getMinSize());
        val tags: ArrayList[Tag] = new ArrayList[Tag]();
        tags.add(new Tag().withKey(GROUP_TYPE_TAG).withValue(SPOT_GROUP_TYPE));
        tags.add(new Tag().withKey(NAME_TAG).withValue(createAutoScalingGroupRequest.getAutoScalingGroupName()));
        createAutoScalingGroupRequest.setTags(tags);
        createAutoScalingGroupRequest;
    }
	
	private[this] def composeNewLaunchConfigurationRequest(instanceType: String, spotPrice: String)(implicit launchConfiguration: LaunchConfiguration): CreateLaunchConfigurationRequest =
    {
        val createLaunchConfigurationRequest: CreateLaunchConfigurationRequest = new CreateLaunchConfigurationRequest();
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId());
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName());
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups());
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData());
        createLaunchConfigurationRequest.setInstanceType(instanceType);
        createLaunchConfigurationRequest.setSpotPrice(spotPrice);
        createLaunchConfigurationRequest.setLaunchConfigurationName(newInstanceTypeLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName(), instanceType));
        createLaunchConfigurationRequest;
    }
	
	private[this] def newInstanceTypeLaunchConfigurationName(launchConfigurationName: String, instanceType: String): String =
    {
        return launchConfigurationName + "-" + instanceType;
    }
}

