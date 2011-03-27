/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.websphere.remote_7;

import java.io.File;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

import javax.jms.IllegalStateException;
import javax.management.MalformedObjectNameException;
import javax.management.NotificationFilterSupport;
import javax.management.ObjectName;

import org.jboss.arquillian.protocol.servlet.ServletMethodExecutor;
import org.jboss.arquillian.spi.client.container.DeployableContainer;
import org.jboss.arquillian.spi.client.container.DeploymentException;
import org.jboss.arquillian.spi.client.container.LifecycleException;
import org.jboss.arquillian.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import com.ibm.websphere.management.AdminClient;
import com.ibm.websphere.management.AdminClientFactory;
import com.ibm.websphere.management.application.AppConstants;
import com.ibm.websphere.management.application.AppManagement;
import com.ibm.websphere.management.application.AppManagementProxy;
import com.ibm.websphere.management.application.AppNotification;
import com.ibm.websphere.management.application.client.AppDeploymentController;

/**
 * WebSphereRemoteContainer
 *
 * @author <a href="mailto:aslak@redhat.com">Aslak Knutsen</a>
 * @author <a href="mailto:gerhard.poul@gmail.com">Gerhard Poul</a>
 * @version $Revision: $
 */
public class WebSphereRemoteContainer implements DeployableContainer<WebSphereRemoteContainerConfiguration>
{
   //-------------------------------------------------------------------------------------||
   // Instance Members -------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||
   
   private static Logger log = Logger.getLogger(WebSphereRemoteContainer.class.getName());
   
   private WebSphereRemoteContainerConfiguration containerConfiguration;

   private AdminClient adminClient;

   //-------------------------------------------------------------------------------------||
   // Required Implementations - DeployableContainer -------------------------------------||
   //-------------------------------------------------------------------------------------||

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.DeployableContainer#setup(org.jboss.arquillian.spi.Context, org.jboss.arquillian.spi.Configuration)
    */
   public void setup(WebSphereRemoteContainerConfiguration configuration)
   {
	   this.containerConfiguration = configuration;
   }

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.DeployableContainer#start(org.jboss.arquillian.spi.Context)
    */
   public void start() throws LifecycleException
   {
      Properties wasServerProps = new Properties();
      wasServerProps.setProperty(AdminClient.CONNECTOR_HOST, containerConfiguration.getRemoteServerAddress());
      wasServerProps.setProperty(AdminClient.CONNECTOR_PORT, String.valueOf(containerConfiguration.getRemoteServerSoapPort()));
      wasServerProps.setProperty(AdminClient.CONNECTOR_TYPE, AdminClient.CONNECTOR_TYPE_SOAP);
      wasServerProps.setProperty(AdminClient.USERNAME, containerConfiguration.getUsername());
      
      if (containerConfiguration.getSecurityEnabled().equalsIgnoreCase("true"))
      {
         wasServerProps.setProperty(AdminClient.CONNECTOR_SECURITY_ENABLED, "true");
         wasServerProps.setProperty(AdminClient.PASSWORD, containerConfiguration.getPassword());
         wasServerProps.setProperty(AdminClient.CACHE_DISABLED, "false"); 
         wasServerProps.setProperty("javax.net.ssl.trustStore", containerConfiguration.getSslTrustStore());
         wasServerProps.setProperty("javax.net.ssl.keyStore", containerConfiguration.getSslKeyStore());
         wasServerProps.setProperty("javax.net.ssl.trustStorePassword", containerConfiguration.getSslTrustStorePassword());
         wasServerProps.setProperty("javax.net.ssl.keyStorePassword", containerConfiguration.getSslKeyStorePassword());
      } else {
         wasServerProps.setProperty(AdminClient.CONNECTOR_SECURITY_ENABLED, "false");
      }
      
      try
      {
         adminClient = AdminClientFactory.createAdminClient(wasServerProps);
         
         ObjectName serverMBean = adminClient.getServerMBean();
         String processType = serverMBean.getKeyProperty("processType");
         
         log.fine("CanonicalKeyPropertyListString: " + serverMBean.getCanonicalKeyPropertyListString());
         
         if (processType.equals("DeploymentManager")
               || processType.equals("NodeAgent")
               || processType.equals("ManagedProcess"))
            throw new IllegalStateException("Connecting to a " + processType + " is not supported.");
      } 
      catch (Exception e) 
      {
         throw new LifecycleException("Could not create AdminClient: " + e.getMessage(), e);
      }
   }

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.DeployableContainer#deploy(org.jboss.arquillian.spi.Context, org.jboss.shrinkwrap.api.Archive)
    */
   public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException
   {
      String appName = createDeploymentName(archive.getName());
      String appExtension = createDeploymentExtension(archive.getName());
      
      File exportedArchiveLocation = null;

      try
      {
         exportedArchiveLocation = File.createTempFile(appName, appExtension);
         archive.as(ZipExporter.class).exportTo(exportedArchiveLocation, true);
         
         Hashtable<Object, Object> prefs = new Hashtable<Object, Object>();
         
         prefs.put(AppConstants.APPDEPL_LOCALE, Locale.getDefault());

         Properties props = new Properties();
         prefs.put (AppConstants.APPDEPL_DFLTBNDG, props);
         props.put (AppConstants.APPDEPL_DFLTBNDG_VHOST, "default_host");

         // Prepare application for deployment to WebSphere Application Server
         AppDeploymentController controller = AppDeploymentController
         	.readArchive(exportedArchiveLocation.getAbsolutePath(), prefs);

         String[] validationResult = controller.validate();
         if (validationResult != null && validationResult.length > 0) {
            throw new DeploymentException("Unable to complete all task data for deployment preparation. Reason: " + Arrays.toString(validationResult));
         }
         
         controller.saveAndClose();
         
         Hashtable<Object, Object> module2Server = new Hashtable<Object, Object>();
         ObjectName serverMBean = adminClient.getServerMBean();
         
         String targetServer = "WebSphere:cell=" + serverMBean.getKeyProperty("cell")
                              + ",node=" + serverMBean.getKeyProperty("node")
                              + ",server=" + serverMBean.getKeyProperty("process");
         
         log.info("Target server for deployment is " + targetServer);
   
         module2Server.put("*",targetServer);
         
         prefs.put(AppConstants.APPDEPL_MODULE_TO_SERVER, module2Server);
         prefs.put(AppConstants.APPDEPL_ARCHIVE_UPLOAD, Boolean.TRUE);
         
         AppManagement appManagementProxy = AppManagementProxy.getJMXProxyForClient(adminClient);
         
         NotificationFilterSupport filterSupport = new NotificationFilterSupport();
         filterSupport.enableType(AppConstants.NotificationType);
         DeploymentNotificationListener listener = new DeploymentNotificationListener(
                  adminClient, 
                  filterSupport, 
                  "Install " + appName,
                  AppNotification.INSTALL);
         
         appManagementProxy.installApplication(
               exportedArchiveLocation.getAbsolutePath(),
               appName, 
               prefs,
               null);
         
         synchronized(listener) 
         {
            listener.wait();
         }

         if(!listener.isSuccessful())
            throw new IllegalStateException("Application not sucessfully deployed: " + listener.getMessage());            

         DeploymentNotificationListener distributionListener = null;
         int checkCount = 0;
         while (checkDistributionStatus(distributionListener) != AppNotification.DISTRIBUTION_DONE
               && ++checkCount < 300)
         {
            Thread.sleep(1000);
            
            distributionListener = new DeploymentNotificationListener(
                  adminClient,
                  filterSupport,
                  null,
                  AppNotification.DISTRIBUTION_STATUS_NODE);
            
            synchronized(distributionListener)
            {
               appManagementProxy.getDistributionStatus(appName, new Hashtable<Object, Object>(), null);
               distributionListener.wait();
            }
         }

         if (checkCount < 300)
         {
            String targetsStarted = appManagementProxy.startApplication(appName, null, null);
            log.info("Application was started on the following targets: " + targetsStarted);
         } else {
            throw new IllegalStateException("Distribution of application did not succeed to all nodes.");
         }
      } 
      catch (Exception e) 
      {
         throw new DeploymentException("Could not deploy application", e);
      }
      finally
      {
         if(exportedArchiveLocation != null) 
         {  
            exportedArchiveLocation.delete();
         }
      }

      ProtocolMetaData metaData = new ProtocolMetaData();
      
   	HTTPContext httpContext = new HTTPContext(
   			containerConfiguration.getRemoteServerAddress(),
   			containerConfiguration.getRemoteServerHttpPort());
      httpContext.add(new Servlet(ServletMethodExecutor.ARQUILLIAN_SERVLET_NAME, "arquillian-protocol"));
      metaData.addContext(httpContext);
      
      return metaData;
   }

   /*
    * Checks the listener and figures out the aggregate distribution status of all nodes
    */
   private String checkDistributionStatus(DeploymentNotificationListener listener) throws MalformedObjectNameException, NullPointerException, IllegalStateException {
      String distributionState = AppNotification.DISTRIBUTION_UNKNOWN;
      if (listener != null)
      {
        String compositeStatus = listener.getNotificationProps()
           .getProperty(AppNotification.DISTRIBUTION_STATUS_COMPOSITE);
        if (compositeStatus != null)
        {
           log.finer("compositeStatus: " + compositeStatus);
           String[] serverStati = compositeStatus.split("\\+");
           int countTrue = 0, countFalse = 0, countUnknown = 0;
           for (String serverStatus : serverStati)
           {
              ObjectName objectName = new ObjectName(serverStatus);
              distributionState = objectName.getKeyProperty("distribution");
              log.finer("distributionState: " + distributionState);
              if (distributionState.equals("true"))
                 countTrue++;
              if (distributionState.equals("false"))
                 countFalse++;
              if (distributionState.equals("unknown"))
                 countUnknown++;
           }
           if (countUnknown > 0)
           {
              distributionState = AppNotification.DISTRIBUTION_UNKNOWN;
           } else if (countFalse > 0) {
              distributionState = AppNotification.DISTRIBUTION_NOT_DONE;
           } else if (countTrue > 0) {
              distributionState = AppNotification.DISTRIBUTION_DONE;
           } else {
              throw new IllegalStateException("Reported distribution status is invalid.");
           }
        }
      }
      return distributionState;
   }

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.DeployableContainer#undeploy(org.jboss.arquillian.spi.Context, org.jboss.shrinkwrap.api.Archive)
    */
   public void undeploy(final Archive<?> archive) throws DeploymentException
   {
      String appName = createDeploymentName(archive.getName());
      
      try
      {
//         Session configSession = new Session(containerConfiguraiton.getUsername(), false);
//         ConfigServiceProxy configProxy = new ConfigServiceProxy(adminClient);

         Hashtable<Object, Object> prefs = new Hashtable<Object, Object>();

         NotificationFilterSupport filterSupport = new NotificationFilterSupport();
         filterSupport.enableType(AppConstants.NotificationType);
         DeploymentNotificationListener listener = new DeploymentNotificationListener(
                  adminClient, 
                  filterSupport, 
                  "Uninstall " + appName,
                  AppNotification.UNINSTALL);
         
         AppManagement appManagementProxy = AppManagementProxy.getJMXProxyForClient(adminClient);
         
         appManagementProxy.uninstallApplication(
               appName, 
               prefs,
               null);
//               configSession.getSessionId());
         
         synchronized(listener) 
         {
            listener.wait();
         }
         if(listener.isSuccessful())
         {
            //configProxy.save(configSession, true);
         }
         else
         {
            throw new IllegalStateException("Application not sucessfully undeployed: " + listener.getMessage());
            //configProxy.discard(configSession);
         }
      } 
      catch (Exception e) 
      {
         throw new DeploymentException("Could not undeploy application", e);
      }
   }

   /* (non-Javadoc)
    * @see org.jboss.arquillian.spi.DeployableContainer#stop(org.jboss.arquillian.spi.Context)
    */
   public void stop() throws LifecycleException
   {
   }

   //-------------------------------------------------------------------------------------||
   // Internal Helper Methods ------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||
   
   private String createDeploymentName(String archiveName) 
   {
      return archiveName.substring(0, archiveName.lastIndexOf("."));
   }

   private String createDeploymentExtension(String archiveName) 
   {
      return archiveName.substring(archiveName.lastIndexOf("."));
   }

	public Class<WebSphereRemoteContainerConfiguration> getConfigurationClass() {
		// TODO Auto-generated method stub
		return WebSphereRemoteContainerConfiguration.class;
	}
	
	public ProtocolDescription getDefaultProtocol() {
		return new ProtocolDescription("Servlet 2.5");
	}
	
	public void deploy(Descriptor descriptor) throws DeploymentException {
		// TODO Auto-generated method stub
		
	}
	
	public void undeploy(Descriptor descriptor) throws DeploymentException {
		// TODO Auto-generated method stub
		
	}
}
