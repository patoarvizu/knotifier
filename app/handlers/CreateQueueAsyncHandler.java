package handlers;

import java.util.Map;

import play.Logger;
import handlers.DescribeAutoScalingGroupsAsyncHandler;

import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;

public class CreateQueueAsyncHandler extends BaseAsyncHandler<CreateQueueRequest, CreateQueueResult>
{
    private Map<String, Integer> capacities;
    
    public CreateQueueAsyncHandler(Map<String, Integer> capacities)
    {
        this.capacities = capacities;
    }
    
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
                        notification, queue, capacities));
            }
        }
        catch (JSONException e)
        {
            Logger.debug(e.getMessage());
        }
    }
}