package handlers;

import play.Logger;
import util.BaseAmazonClient;

import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.handlers.AsyncHandler;

public abstract class BaseAsyncHandler<REQUEST extends AmazonWebServiceRequest, RESULT> implements AsyncHandler<REQUEST, RESULT>, BaseAmazonClient
{
    @Override
    public void onError(Exception e)
    {
        Logger.debug(e.getMessage());
    }
}