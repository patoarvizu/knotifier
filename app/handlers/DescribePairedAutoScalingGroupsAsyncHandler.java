package handlers;

import java.util.Map;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;

public class DescribePairedAutoScalingGroupsAsyncHandler extends BaseAsyncHandler<DescribeAutoScalingGroupsRequest, DescribeAutoScalingGroupsResult>
{
    private final String pairedASGroupName;
    private Map<String, Integer> capacities;
    
    public DescribePairedAutoScalingGroupsAsyncHandler(
            String pairedASGroupName, Map<String, Integer> capacities)
    {
        this.pairedASGroupName = pairedASGroupName;
        this.capacities = capacities;
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