package actors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import play.Logger;
import play.libs.Akka;
import scala.concurrent.Future;
import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.TypedActor;
import akka.actor.TypedProps;
import akka.dispatch.OnComplete;
import akka.util.Timeout;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsyncClient;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest;
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult;
import com.amazonaws.services.autoscaling.model.LaunchConfiguration;
import com.amazonaws.services.autoscaling.model.TagDescription;
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.SpotPrice;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;

public class AutoScaleModifierImpl implements AutoScaleModifier
{
    private Map<String, Integer> capacities = new HashMap<String, Integer>();
    private Map<InstanceType, SpotPrice> lowestPrices = new HashMap<InstanceType, SpotPrice>();
    private static Timeout TIMEOUT = new Timeout(20, TimeUnit.SECONDS);
    private ActorContext typedActorContext = TypedActor.context();
    private PriceMonitor priceMonitor;
    private AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();
    private AmazonSQSAsyncClient sqsClient = new AmazonSQSAsyncClient(credentials);
    private AmazonAutoScalingAsyncClient asClient = new AmazonAutoScalingAsyncClient(credentials);
    
    public AutoScaleModifierImpl()
    {
        Future<ActorRef> priceMonitorActorRefFuture = Akka.system()
                .actorSelection("akka://application/user/priceMonitor")
                .resolveOne(TIMEOUT);
        priceMonitorActorRefFuture.onComplete(new OnComplete<ActorRef>()
        {
            @Override
            public void onComplete(Throwable e, ActorRef priceMonitorActorRef)
                    throws Throwable
            {
                if (e != null) throw e;
                priceMonitor = TypedActor.get(typedActorContext).typedActorOf(
                        new TypedProps<PriceMonitorImpl>(PriceMonitor.class,
                                PriceMonitorImpl.class), priceMonitorActorRef);
            }
        }, Akka.system().dispatcher());
    }

    @Override
    public void monitorAutoScaleGroups() throws Exception
    {
        if(priceMonitor != null)
            lowestPrices.putAll(priceMonitor.getPrices());
        sqsClient.createQueueAsync(new CreateQueueRequest("load-balancer-test"), new CreateQueueResultAsyncHandler());
        for(String pairedASGroupName : capacities.keySet())
        {
            Logger.debug("New capacity for " + pairedASGroupName + ": " + capacities.get(pairedASGroupName));
            DescribeAutoScalingGroupsResult describeAutoScalingGroups = asClient.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(pairedASGroupName));
            if(!describeAutoScalingGroups.getAutoScalingGroups().isEmpty())
            {
                AutoScalingGroup autoScalingGroup = describeAutoScalingGroups.getAutoScalingGroups().get(0);
                String launchConfigurationName = autoScalingGroup.getLaunchConfigurationName();
                DescribeLaunchConfigurationsResult describeLaunchConfigurationsResult = asClient.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest().withLaunchConfigurationNames(launchConfigurationName));
                LaunchConfiguration launchConfiguration = describeLaunchConfigurationsResult.getLaunchConfigurations().get(0);
                String instanceType = discoverInstanceType();
                String newLaunchConfigurationName = launchConfiguration.getLaunchConfigurationName() + "-" + instanceType;
                DescribeLaunchConfigurationsResult describeLaunchConfigurations = asClient.describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest().withLaunchConfigurationNames(newLaunchConfigurationName));
                if(describeLaunchConfigurations.getLaunchConfigurations().isEmpty())
                {
                    CreateLaunchConfigurationRequest createLaunchConfigurationRequest = getNewLaunchConfigurationRequest(launchConfiguration, instanceType);
                    asClient.createLaunchConfiguration(createLaunchConfigurationRequest);
                }
                asClient.updateAutoScalingGroupAsync(new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(pairedASGroupName).withDesiredCapacity(capacities.get(pairedASGroupName)).withLaunchConfigurationName(newLaunchConfigurationName));
            }
        }
        capacities.clear();
    }
    
    private String discoverInstanceType()
    {
        return InstanceType.C3Large.toString();
    }
    
    private CreateLaunchConfigurationRequest getNewLaunchConfigurationRequest(LaunchConfiguration launchConfiguration, String instanceType)
    {
        CreateLaunchConfigurationRequest createLaunchConfigurationRequest = new CreateLaunchConfigurationRequest();
        createLaunchConfigurationRequest.setImageId(launchConfiguration.getImageId());
        createLaunchConfigurationRequest.setKeyName(launchConfiguration.getKeyName());
        createLaunchConfigurationRequest.setSecurityGroups(launchConfiguration.getSecurityGroups());
        createLaunchConfigurationRequest.setUserData(launchConfiguration.getUserData());
        createLaunchConfigurationRequest.setInstanceType(instanceType);
        createLaunchConfigurationRequest.setSpotPrice(launchConfiguration.getSpotPrice());
        createLaunchConfigurationRequest.setLaunchConfigurationName(launchConfiguration.getLaunchConfigurationName() + "-" + instanceType);
        return createLaunchConfigurationRequest;
    }

    private final class DescribePairedAutoScalingGroupsAsyncHandler
            implements
            AsyncHandler<DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult>
    {
        private String pairedASGroupName;

        private DescribePairedAutoScalingGroupsAsyncHandler(
                String pairedASGroupName)
        {
            this.pairedASGroupName = pairedASGroupName;
        }
    
        @Override
        public void onError(
                Exception e)
        {
            Logger.debug(e.getMessage());
        }
    
        @Override
        public void onSuccess(
                DescribeAutoScalingGroupsRequest request,
                DescribeAutoScalingGroupsResult pairedASGroupResult)
        {
            if(!pairedASGroupResult.getAutoScalingGroups().isEmpty())
            {
                AutoScalingGroup pairedASGroup = pairedASGroupResult.getAutoScalingGroups().get(0);
                capacities.put(pairedASGroupName, pairedASGroup.getDesiredCapacity() + 1);
            }
        }
    }

    private final class DescribeAutoScalingGroupsAsyncHandler
            implements
            AsyncHandler<DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult>
    {
        private Message sqsMessage;
        private JSONObject notification;
        private CreateQueueResult queue;
    
        private DescribeAutoScalingGroupsAsyncHandler(Message sqsMessage,
                JSONObject notification, CreateQueueResult queue)
        {
            this.sqsMessage = sqsMessage;
            this.notification = notification;
            this.queue = queue;
        }
    
        @Override
        public void onError(Exception e)
        {
            Logger.debug(e.getMessage());
        }
    
        @Override
        public void onSuccess(
                DescribeAutoScalingGroupsRequest request,
                DescribeAutoScalingGroupsResult notificationASGroup)
        {
            try
            {
                String pairedASGroupName;
                String groupType;
                if(!notificationASGroup.getAutoScalingGroups().isEmpty())
                {
                    Map<String, String> tags = getTagMap(notificationASGroup.getAutoScalingGroups().get(0).getTags());
                    groupType = tags.get("GroupType");
                    pairedASGroupName = tags.get("PairedASGroup");
                    if("autoscaling:EC2_INSTANCE_TERMINATE".equals(notification.getString("Event")) && "OnDemand".equals(groupType))
                    {
                        if(!capacities.containsKey(pairedASGroupName))
                        {
                            asClient.describeAutoScalingGroupsAsync(
                                    new DescribeAutoScalingGroupsRequest()
                                            .withAutoScalingGroupNames(pairedASGroupName),
                                    new DescribePairedAutoScalingGroupsAsyncHandler(
                                            pairedASGroupName));
                        }
                        else
                        {
                            capacities.put(pairedASGroupName, capacities.get(pairedASGroupName) + 1);
                        }
                        sqsClient.deleteMessageAsync(new DeleteMessageRequest(queue.getQueueUrl(), sqsMessage.getReceiptHandle()));
                    }
                }
            }
            catch (Exception e)
            {
                Logger.debug(e.getMessage());
            }
        }
    }
    
    private Map<String, String> getTagMap(List<TagDescription> tagDescriptions)
    {
        HashMap<String, String> tags = new HashMap<String, String>();
        for(TagDescription tagDescription : tagDescriptions)
        {
            tags.put(tagDescription.getKey(), tagDescription.getValue());
        }
        return tags;
    }

    private final class CreateQueueResultAsyncHandler implements
            AsyncHandler<CreateQueueRequest, CreateQueueResult>
    {
        @Override
        public void onSuccess(CreateQueueRequest request, CreateQueueResult queue)
        {
            try
            {
                ReceiveMessageResult sqsMessages = sqsClient.receiveMessage(new ReceiveMessageRequest().withMaxNumberOfMessages(10).withQueueUrl(queue.getQueueUrl()));
                for(Message sqsMessage : sqsMessages.getMessages())
                {
                    JSONObject message = new JSONObject(sqsMessage.getBody());
                    JSONObject notification = new JSONObject(message.getString("Message"));
                    String autoScalingGroupName = notification.getString("AutoScalingGroupName");
                    DescribeAutoScalingGroupsRequest autoScalingGroupsRequest = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(autoScalingGroupName);
                    asClient.describeAutoScalingGroupsAsync(autoScalingGroupsRequest, new DescribeAutoScalingGroupsAsyncHandler(sqsMessage,
                            notification, queue));
                }
            }
            catch (JSONException e)
            {
                Logger.debug(e.getMessage());
            }
        }
    
        @Override
        public void onError(Exception e)
        {
            Logger.debug(e.getMessage());
        }
    }
}