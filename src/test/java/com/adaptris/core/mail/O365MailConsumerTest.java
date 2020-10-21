package com.adaptris.core.mail;

import com.adaptris.core.AdaptrisMessage;
import com.adaptris.core.FixedIntervalPoller;
import com.adaptris.core.MultiPayloadMessageFactory;
import com.adaptris.core.Poller;
import com.adaptris.core.QuartzCronPoller;
import com.adaptris.core.StandaloneConsumer;
import com.adaptris.core.stubs.MockMessageListener;
import com.adaptris.core.util.LifecycleHelper;
import com.adaptris.interlok.junit.scaffolding.ExampleConsumerCase;
import com.adaptris.util.TimeInterval;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class O365MailConsumerTest extends ExampleConsumerCase
{
  private static final String APPLICATION_ID = "47ea49b0-670a-47c1-9303-0b45ffb766ec";
  private static final String TENANT_ID = "cbf4a38d-3117-48cd-b54b-861480ee93cd";
  private static final String CLIENT_SECRET = "NGMyYjY0MTEtOTU0Ny00NTg0LWE3MzQtODg2ZDAzZGVmZmY1Cg==";
  private static final String USERNAME = "user@example.com";

  private static final Poller[] POLLERS =
  {
    new FixedIntervalPoller(new TimeInterval(60L, TimeUnit.SECONDS)),
    new QuartzCronPoller("0 */5 * * * ?"),
  };

  private O365MailConsumer consumer;

  private boolean runTests = false;

  @Before
  public void setUp()
  {
    Properties properties = new Properties();
    try
    {
      properties.load(new FileInputStream(this.getClass().getResource("o365.properties").getFile()));
      runTests = true;
    }
    catch (Exception e)
    {
      // do nothing
    }

    consumer = new O365MailConsumer();

    consumer.setApplicationId(properties.getProperty("APPLICATION_ID", APPLICATION_ID));
    consumer.setTenantId(properties.getProperty("TENANT_ID", TENANT_ID));
    consumer.setClientSecret(properties.getProperty("CLIENT_SECRET", CLIENT_SECRET));
    consumer.setUsername(properties.getProperty("USERNAME", USERNAME));

    consumer.setMessageFactory(new MultiPayloadMessageFactory());
  }

  @Test
  public void testConsumer() throws Exception
  {
    Assume.assumeTrue(runTests);

    MockMessageListener mockMessageListener = new MockMessageListener(10);
    StandaloneConsumer standaloneConsumer = new StandaloneConsumer(consumer);
    standaloneConsumer.registerAdaptrisMessageListener(mockMessageListener);
    try
    {
      LifecycleHelper.init(standaloneConsumer);
      LifecycleHelper.prepare(standaloneConsumer);
      LifecycleHelper.start(standaloneConsumer);

      waitForMessages(mockMessageListener, 5, 5000); // wait until we get five new emails or for 5 seconds

      List<AdaptrisMessage> messages = mockMessageListener.getMessages();

      System.out.println("Found " + messages.size() + " emails");
      Thread.sleep(5000); // sleep for 5 seconds, otherwise the Graph SDK complains we disconnected while waiting for a response
    }
    catch (InterruptedIOException | InterruptedException e)
    {
      // Ignore these as they're occasionally thrown by the Graph SDK teh connection is closed while it's still processing
    }
    finally
    {
      stop(standaloneConsumer);
    }
  }

  @Override
  protected Object retrieveObjectForSampleConfig()
  {
    return new StandaloneConsumer(consumer);
  }

  @Override
  protected List<StandaloneConsumer> retrieveObjectsForSampleConfig()
  {
    List<StandaloneConsumer> result = new ArrayList();
    for (Poller poller : POLLERS)
    {
      StandaloneConsumer standaloneConsumer = (StandaloneConsumer)retrieveObjectForSampleConfig();
      ((O365MailConsumer)standaloneConsumer.getConsumer()).setPoller(poller);
      result.add(standaloneConsumer);
    }
    return result;
  }
}
