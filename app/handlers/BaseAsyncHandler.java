package handlers;

import play.Logger;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.AsyncHandler;

public abstract class BaseAsyncHandler<REQUEST extends AmazonWebServiceRequest, RESULT> extends BaseAmazonClient implements AsyncHandler<REQUEST, RESULT>
{
    @Override
    public void onError(Exception e)
    {
        Logger.debug(e.getMessage());
    }
}