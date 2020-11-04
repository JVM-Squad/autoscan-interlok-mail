package com.adaptris.core.mail;

import com.adaptris.annotation.AdapterComponent;
import com.adaptris.annotation.AdvancedConfig;
import com.adaptris.annotation.ComponentProfile;
import com.adaptris.annotation.DisplayOrder;
import com.adaptris.annotation.InputFieldDefault;
import com.adaptris.annotation.InputFieldHint;
import com.adaptris.core.AdaptrisMessage;
import com.adaptris.core.CoreException;
import com.adaptris.core.MultiPayloadAdaptrisMessage;
import com.adaptris.core.ProduceException;
import com.adaptris.core.ProduceOnlyProducerImp;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.graph.concurrency.ChunkedUploadProvider;
import com.microsoft.graph.concurrency.IProgressCallback;
import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.models.extensions.Attachment;
import com.microsoft.graph.models.extensions.AttachmentItem;
import com.microsoft.graph.models.extensions.EmailAddress;
import com.microsoft.graph.models.extensions.FileAttachment;
import com.microsoft.graph.models.extensions.IGraphServiceClient;
import com.microsoft.graph.models.extensions.ItemBody;
import com.microsoft.graph.models.extensions.Message;
import com.microsoft.graph.models.extensions.Recipient;
import com.microsoft.graph.models.extensions.UploadSession;
import com.microsoft.graph.models.generated.AttachmentType;
import com.microsoft.graph.models.generated.BodyType;
import com.microsoft.graph.requests.extensions.AttachmentCollectionPage;
import com.microsoft.graph.requests.extensions.AttachmentCollectionRequest;
import com.microsoft.graph.requests.extensions.GraphServiceClient;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import javax.validation.constraints.NotBlank;
import java.io.InputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Implementation of an email producer that is geared towards Microsoft
 * Office 365, using their Graph API and OAuth2.
 *
 * @config office-365-mail-producer
 */
@XStreamAlias("office-365-mail-producer")
@AdapterComponent
@ComponentProfile(summary = "Send email using a Microsoft Office 365 account using the Microsoft Graph API", tag = "producer,email,o365,microsoft,office,outlook,365")
@DisplayOrder(order = { "applicationId", "tenantId", "clientSecret", "username", "subject", "toRecipients", "ccRecipients", "bccRecipients", "save" })
public class O365MailProducer extends ProduceOnlyProducerImp
{
  private static final String SCOPE = "https://graph.microsoft.com/.default";

  @Getter
  @Setter
  @NotBlank
  private String applicationId;

  @Getter
  @Setter
  @NotBlank
  private String tenantId;

  @Getter
  @Setter
  @NotBlank
  private String clientSecret;

  @Getter
  @Setter
  @NotBlank
  @InputFieldHint(expression = true)
  private String username;

  @Getter
  @Setter
  @InputFieldHint(expression = true)
  private String subject;

  @Getter
  @Setter
  @NotBlank
  @InputFieldHint(friendly = "Comma separated list of email addresses", expression = true)
  private String toRecipients;

  @Getter
  @Setter
  @AdvancedConfig
  @InputFieldHint(friendly = "Comma separated list of email addresses", expression = true)
  private String ccRecipients;

  @Getter
  @Setter
  @AdvancedConfig
  @InputFieldHint(friendly = "Comma separated list of email addresses", expression = true)
  private String bccRecipients;

  @Getter
  @Setter
  @AdvancedConfig
  @InputFieldHint(friendly = "Save message to sent items?")
  @InputFieldDefault("true")
  private Boolean save;

  private transient ConfidentialClientApplication confidentialClientApplication;

  @Override
  public void prepare() throws CoreException
  {
    try
    {
      confidentialClientApplication = ConfidentialClientApplication.builder(applicationId,
              ClientCredentialFactory.createFromSecret(clientSecret))
              .authority(tenant())
              .build();
    }
    catch (Exception e)
    {
      log.error("Could not identify Azure application or tenant", e);
      throw new CoreException(e);
    }
  }

  @Override
  protected void doProduce(AdaptrisMessage adaptrisMessage, String endpoint) throws ProduceException
  {
    String user = adaptrisMessage.resolve(username);

    log.debug("Sending mail via Office365 as user " + user);

    try
    {
      IAuthenticationResult iAuthResult = confidentialClientApplication.acquireToken(ClientCredentialParameters.builder(Collections.singleton(SCOPE)).build()).join();
      log.trace("Access token: " + iAuthResult.accessToken());
      IGraphServiceClient graphClient = GraphServiceClient.builder().authenticationProvider(request -> request.addHeader("Authorization", "Bearer " + iAuthResult.accessToken())).buildClient();

      Message outlookMessage = new Message();
      outlookMessage.subject = adaptrisMessage.resolve(subject);

      ItemBody body = new ItemBody();
      body.contentType = BodyType.TEXT;
      body.content = adaptrisMessage.getContent();
      outlookMessage.body = body;

      outlookMessage.toRecipients = resolveRecipients(adaptrisMessage, toRecipients);
      outlookMessage.ccRecipients = resolveRecipients(adaptrisMessage, ccRecipients);
      outlookMessage.bccRecipients = resolveRecipients(adaptrisMessage, bccRecipients);

      // TODO Add any custom headers.

      if (adaptrisMessage instanceof MultiPayloadAdaptrisMessage)
      {
        outlookMessage.isDraft = true;
        Message draftMessage = graphClient.users(user).messages().buildRequest().post(outlookMessage);

        MultiPayloadAdaptrisMessage multiPayloadAdaptrisMessage = (MultiPayloadAdaptrisMessage)adaptrisMessage;
        String id = multiPayloadAdaptrisMessage.getCurrentPayloadId();
        for (String name : multiPayloadAdaptrisMessage.getPayloadIDs())
        {
          if (name.equals(id))
          {
            continue;
          }
          long size = multiPayloadAdaptrisMessage.getSize(name);
          /*
           * Depending on the size of the file, you can choose one of
           * two ways to attach a file to a message:
           *  - If the file size is under 4 MB, you can do a single POST
           *    on the attachments navigation property of the message.
           *    The successful POST response includes the ID of the file
           *    attached to the message.
           *  - If the file size is between 3MB and 150MB, create an
           *    upload session, and iteratively use PUT to upload ranges
           *    of bytes of the file until you have uploaded the entire
           *    file. A header in the final successful PUT response
           *    includes a URL with the attachment ID.
           */
          if (size < 4 * FileUtils.ONE_MB)
          {
            FileAttachment attachment = new FileAttachment();
            attachment.oDataType = "#microsoft.graph.fileAttachment";
            attachment.name = name;
            attachment.size = (int)size;
            attachment.contentType = "application/octet-stream";
            attachment.contentBytes = Base64.getEncoder().encode(multiPayloadAdaptrisMessage.getPayload(name));
            graphClient.users(user).messages(draftMessage.id).attachments().buildRequest().post(attachment);
            graphClient.users(user).messages(draftMessage.id).send().buildRequest().post();
          }
          else
          {
            AttachmentItem attachment = new AttachmentItem();
            attachment.attachmentType = AttachmentType.FILE;
            attachment.name = name;
            attachment.size = size;
            attachment.contentType = "application/octet-stream";

            UploadSession uploadSession = graphClient.users(user).messages(draftMessage.id).attachments().createUploadSession(attachment).buildRequest().post();
            ChunkedUploadProvider<AttachmentItem> chunkedUploadProvider = new ChunkedUploadProvider<>(uploadSession, graphClient, multiPayloadAdaptrisMessage.getInputStream(name), attachment.size, AttachmentItem.class);
            chunkedUploadProvider.upload(new IProgressCallback()
            {
              @Override
              public void success(Object o)
              {
                graphClient.users(user).messages(draftMessage.id).send().buildRequest().post();
              }

              @Override
              public void failure(ClientException ex)
              {
                log.error("Could not process attachment {} for message {} : {}", attachment.name, adaptrisMessage.getUniqueId(), ex);
              }

              @Override
              public void progress(long current, long max)
              {
                // do nothing
              }
            });
          }
        }
      }
      else
      {
        graphClient.users(user).sendMail(outlookMessage, save()).buildRequest().post();
      }
    }
    catch (Exception e)
    {
      log.error("Exception processing Outlook message", e);
      throw new ProduceException(e);
    }
  }

  @Override
  public String endpoint(AdaptrisMessage adaptrisMessage)
  {
    return adaptrisMessage.resolve(toRecipients);
  }

  private static List<Recipient> resolveRecipients(AdaptrisMessage adaptrisMessage, String recipients)
  {
    if (StringUtils.isEmpty(recipients))
    {
      return null;
    }
    List<Recipient> recipientList = new LinkedList<>();
    for (String address : adaptrisMessage.resolve(recipients).split(","))
    {
      Recipient recipient = new Recipient();
      EmailAddress emailAddress = new EmailAddress();
      emailAddress.address = address;
      recipient.emailAddress = emailAddress;
      recipientList.add(recipient);
    }
    return recipientList;
  }

  private String tenant()
  {
    return String.format("https://login.microsoftonline.com/%s", tenantId);
  }

  private boolean save()
  {
    return BooleanUtils.toBooleanDefaultIfNull(save, true);
  }
}
