package handlers;

import java.util.Map;

import play.Logger;
import handlers.DescribePairedAutoScalingGroupsAsyncHandler;

import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.TagDescription;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.util.json.JSONObject;

public class DescribeAutoScalingGroupsAsyncHandler extends BaseAsyncHandler<DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult>
{
    private Message sqsMessage;
    private JSONObject notification;
    private CreateQueueResult queue;
    private Map<String, Integer> capacities;

    public DescribeAutoScalingGroupsAsyncHandler(Message sqsMessage,
            JSONObject notification, CreateQueueResult queue, Map<String, Integer> capacities)
    {
        this.sqsMessage = sqsMessage;
        this.notification = notification;
        this.queue = queue;
        this.capacities = capacities;
    }

    @Override
    public void onSuccess(
            DescribeAutoScalingGroupsRequest request,
            DescribeAutoScalingGroupsResult notificationASGroup)
    {
        try
        {
            final String pairedASGroupName;
            if(!notificationASGroup.getAutoScalingGroups().isEmpty())
            {
                for (TagDescription tagDescription : notificationASGroup.getAutoScalingGroups().get(0).getTags())
                {
                    if (tagDescription.getKey().equals("PairedASGroup"))
                    {
                        pairedASGroupName = tagDescription.getValue();
                        if("autoscaling:EC2_INSTANCE_TERMINATE".equals(notification.getString("Event")))
                        {
                            if(!capacities.containsKey(pairedASGroupName))
                            {
                                asClient.describeAutoScalingGroupsAsync(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(pairedASGroupName), new DescribePairedAutoScalingGroupsAsyncHandler(
                                        pairedASGroupName, capacities));
                            }
                            else
                            {
                                capacities.put(pairedASGroupName, capacities.get(pairedASGroupName) + 1);
                            }
                            sqsClient.deleteMessageAsync(new DeleteMessageRequest(queue.getQueueUrl(), sqsMessage.getReceiptHandle()));
                        }
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Logger.debug(e.getMessage());
        }
    }
}