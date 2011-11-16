/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.security.plugins.authorization;

import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.jboss.logging.Logger;
import org.jboss.security.ErrorCodes;
import org.jboss.security.SecurityConstants;
import org.jboss.security.authorization.AuthorizationContext;
import org.jboss.security.authorization.AuthorizationException;
import org.jboss.security.authorization.AuthorizationModule;
import org.jboss.security.authorization.Resource;
import org.jboss.security.authorization.ResourceKeys;
import org.jboss.security.authorization.ResourceType;
import org.jboss.security.authorization.config.AuthorizationModuleEntry;
import org.jboss.security.authorization.modules.DelegatingAuthorizationModule;
import org.jboss.security.config.ApplicationPolicy;
import org.jboss.security.config.AuthorizationInfo;
import org.jboss.security.config.ControlFlag;
import org.jboss.security.config.SecurityConfiguration;
import org.jboss.security.identity.Role;
import org.jboss.security.identity.RoleGroup;
import org.jboss.security.plugins.ClassLoaderLocator;
import org.jboss.security.plugins.ClassLoaderLocatorFactory;

//$Id: JBossAuthorizationContext.java 62954 2007-05-10 04:12:18Z anil.saldhana@jboss.com $

/**
 *  JBAS-3374: Authorization Framework for Policy Decision Modules
 *  For information on the behavior of the Authorization Modules,
 *  For Authorization Modules behavior(Required, Requisite, Sufficient and Optional)
 *  please refer to the javadoc for @see javax.security.auth.login.Configuration
 *  
 *  The AuthorizationContext derives the AuthorizationInfo(configuration for the modules)
 *  in the following way:
 *  a) If there has been an injection of ApplicationPolicy, then it will be used.
 *  b) Util.getApplicationPolicy will be used(which relies on SecurityConfiguration static class).
 *  c) Flag an error that there is no available Application Policy
 *  
 *  @author <a href="mailto:Anil.Saldhana@jboss.org">Anil Saldhana</a>
 *  @since  Jun 11, 2006 
 *  @version $Revision: 62954 $
 */
public class JBossAuthorizationContext extends AuthorizationContext
{
   private static Logger log = Logger.getLogger(JBossAuthorizationContext.class);

   private boolean trace = log.isTraceEnabled();

   private final String EJB = SecurityConstants.DEFAULT_EJB_APPLICATION_POLICY;
   private final String WEB = SecurityConstants.DEFAULT_WEB_APPLICATION_POLICY;

   private Subject authenticatedSubject = null;

   //Application Policy can be injected
   private ApplicationPolicy applicationPolicy = null;
   
   //Clazz instance cache map to minimize load class synchronization
   private static Map< String,Class<?> > clazzMap = new WeakHashMap<String, Class<?>>();

   public JBossAuthorizationContext(String name)
   {
      this.securityDomainName = name; 
   }

   public JBossAuthorizationContext(String name, CallbackHandler handler)
   {
      this(name);
      this.callbackHandler = handler;
   }

   public JBossAuthorizationContext(String name, Subject subject, CallbackHandler handler)
   {
      this(name, handler);
      this.authenticatedSubject = subject;
   }

   /**
    * Inject an ApplicationPolicy that contains AuthorizationInfo
    * @param aPolicy
    * @throws IllegalArgumentException if ApplicationPolicy is null or
    *    does not contain AuthorizationInfo or domain name does not match
    */
   public void setApplicationPolicy(ApplicationPolicy aPolicy)
   {
      if (aPolicy == null)
         throw new IllegalArgumentException(ErrorCodes.NULL_ARGUMENT + "Application Policy is null:domain=" + this.securityDomainName);
      AuthorizationInfo authzInfo = aPolicy.getAuthorizationInfo();
      if (authzInfo == null)
         throw new IllegalArgumentException(ErrorCodes.NULL_VALUE + "Application Policy has no AuthorizationInfo");
      if (!authzInfo.getName().equals(securityDomainName))
         throw new IllegalArgumentException(ErrorCodes.WRONG_TYPE + "Application Policy ->AuthorizationInfo:" + authzInfo.getName()
               + " does not match required domain name=" + this.securityDomainName);
      this.applicationPolicy = aPolicy;
   }

   /**
    * Authorize the Resource
    * @param resource
    * @return AuthorizationContext.PERMIT or AuthorizationContext.DENY
    * @throws AuthorizationException
    */
   public int authorize(final Resource resource) throws AuthorizationException
   {
      return this.authorize(resource, this.authenticatedSubject, (RoleGroup) resource.getMap().get(
            ResourceKeys.SECURITY_CONTEXT_ROLES));
   }

   /**
    * @see AuthorizationContext#authorize(Resource, Role)
    */
   public int authorize(final Resource resource, final Subject subject, final RoleGroup callerRoles)
         throws AuthorizationException
   {  
      final List<AuthorizationModule> modules = new ArrayList<AuthorizationModule>();
      final List<ControlFlag> controlFlags = new ArrayList<ControlFlag>();
      
      try
      {
         this.authenticatedSubject = subject;

         initializeModules(resource, callerRoles, modules, controlFlags); 

         AccessController.doPrivileged(new PrivilegedExceptionAction<Object>()
         {
            public Object run() throws AuthorizationException
            {
               int result = invokeAuthorize(resource, modules, controlFlags);
               if (result == PERMIT)
                  invokeCommit( modules, controlFlags );
               if (result == DENY)
               {
                  invokeAbort( modules, controlFlags );
                  throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "Denied");
               }
               return null;
            }
         });
      }
      catch (PrivilegedActionException e)
      {
         Exception exc = e.getException();
         if (trace)
            log.trace("Error in authorize:", exc);
         invokeAbort( modules, controlFlags );
         throw ((AuthorizationException) exc);
      }
      finally
      { 
         if(modules != null)
            modules.clear();
         if(controlFlags != null )
            controlFlags.clear();  
      }
      return PERMIT;
   }

   //Private Methods  
   private void initializeModules(Resource resource, RoleGroup role, List<AuthorizationModule> modules,
         List<ControlFlag> controlFlags) throws PrivilegedActionException
   {
      AuthorizationInfo authzInfo = getAuthorizationInfo(securityDomainName, resource);
      if (authzInfo == null)
         throw new IllegalStateException(ErrorCodes.NULL_VALUE + "Authorization Info is null");
      
      ClassLoader moduleCL = null;
      String jbossModuleName = authzInfo.getJBossModuleName();
      if(jbossModuleName != null)
      {
    	  ClassLoaderLocator cll = ClassLoaderLocatorFactory.get();
    	  if( cll != null)
    	  {
    		  moduleCL = cll.get(jbossModuleName);
    	  }
      }
      AuthorizationModuleEntry[] entries = authzInfo.getAuthorizationModuleEntry();
      int len = entries != null ? entries.length : 0;
      for (int i = 0; i < len; i++)
      {
    	  AuthorizationModuleEntry entry = entries[i];
    	  ControlFlag flag = entry.getControlFlag();
    	  if (flag == null)
    	  {
    		  if (trace)
    			  log.trace("Null Control flag for entry:" + entry + ". Defaults to REQUIRED!");
    		  flag = ControlFlag.REQUIRED;
    	  }
    	  else if (trace)
    		  log.trace("Control flag for entry:" + entry + "is:[" + flag + "]");

    	  controlFlags.add(flag);
    	  AuthorizationModule module = instantiateModule(moduleCL, entry.getPolicyModuleName(), entry.getOptions(), role); 
    	  modules.add(module);
      }
   }

   private int invokeAuthorize(Resource resource, List<AuthorizationModule> modules,
         List<ControlFlag> controlFlags) throws AuthorizationException
   {
      //Control Flag behavior
      boolean encounteredRequiredError = false;
      boolean encounteredOptionalError = false;
      AuthorizationException moduleException = null;
      int overallDecision = DENY;

      int length = modules.size();
      for (int i = 0; i < length; i++)
      {
         AuthorizationModule module = (AuthorizationModule) modules.get(i);
         ControlFlag flag = (ControlFlag) controlFlags.get(i);
         int decision = DENY;
         try
         {
            decision = module.authorize(resource);
         }
         catch (Exception ae)
         {
            decision = DENY;
            if (moduleException == null)
               moduleException = new AuthorizationException(ae.getMessage());
         }

         if (decision == PERMIT)
         {
            overallDecision = PERMIT;
            //SUFFICIENT case
            if (flag == ControlFlag.SUFFICIENT && encounteredRequiredError == false)
               return PERMIT;
            continue; //Continue with the other modules
         }
         //Go through the failure cases 
         //REQUISITE case
         if (flag == ControlFlag.REQUISITE)
         {
            if (trace)
               log.trace("REQUISITE failed for " + module);
            if (moduleException == null)
               moduleException = new AuthorizationException("Authorization failed");
            else
               throw moduleException;
         }
         //REQUIRED Case
         if (flag == ControlFlag.REQUIRED)
         {
            if (trace)
               log.trace("REQUIRED failed for " + module);
            if (encounteredRequiredError == false)
               encounteredRequiredError = true;
         }
         if (flag == ControlFlag.OPTIONAL)
            encounteredOptionalError = true;
      }

      //All the authorization modules have been visited.
      String msg = getAdditionalErrorMessage(moduleException);
      if (encounteredRequiredError)
         throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "Authorization Failed:" + msg);
      if (overallDecision == DENY && encounteredOptionalError)
         throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "Authorization Failed:" + msg);
      if (overallDecision == DENY)
         throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "Authorization Failed:Denied.");
      return PERMIT;
   }

   private void invokeCommit( List<AuthorizationModule> modules,
         List<ControlFlag> controlFlags ) throws AuthorizationException
   {
      int length = modules.size();
      for (int i = 0; i < length; i++)
      {
         AuthorizationModule module = (AuthorizationModule) modules.get(i);
         boolean bool = module.commit();
         if (!bool)
            throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "commit on modules failed:" + module.getClass());
      }
   }

   private void invokeAbort( List<AuthorizationModule> modules,
         List<ControlFlag> controlFlags ) throws AuthorizationException
   {
      int length = modules.size();
      for (int i = 0; i < length; i++)
      {
         AuthorizationModule module = (AuthorizationModule) modules.get(i);
         boolean bool = module.abort();
         if (!bool)
            throw new AuthorizationException(ErrorCodes.ACCESS_DENIED + "abort on modules failed:" + module.getClass());
      }
   }

   private AuthorizationModule instantiateModule(ClassLoader cl, String name, Map<String, Object> map, RoleGroup subjectRoles)
         throws PrivilegedActionException
   {
      AuthorizationModule am = null;
      try
      {
         Class<?> clazz = clazzMap.get(name);
         if(clazz == null)
         {
            try
            {
               if(cl == null)
               {
            	   cl = getClass().getClassLoader();
               }
               clazz = cl.loadClass(name);
            }
            catch (Exception ignore)
            {
               ClassLoader tcl = SecurityActions.getContextClassLoader();
               clazz = tcl.loadClass(name);
            }
            clazzMap.put(name, clazz);
         }   
            
         am = (AuthorizationModule) clazz.newInstance();
      }
      catch (Exception e)
      {
         if (trace)
            log.debug("Error instantiating AuthorizationModule:", e);
      }
      if (am == null)
         throw new IllegalStateException(ErrorCodes.NULL_VALUE + "AuthorizationModule has not " + "been instantiated");
      am.initialize(this.authenticatedSubject, this.callbackHandler, this.sharedState, map, subjectRoles);
      return am;
   }

   private AuthorizationInfo getAuthorizationInfo(String domainName, Resource resource)
   {
      ResourceType layer = resource.getLayer();

      //Check if an instance of ApplicationPolicy is available 
      if (this.applicationPolicy != null)
         return applicationPolicy.getAuthorizationInfo();

      ApplicationPolicy aPolicy = SecurityConfiguration.getApplicationPolicy(domainName);

      if (aPolicy == null)
      {
         if (trace)
            log.trace("Application Policy not obtained for domain=" + domainName
                  + ". Trying to obtain the App policy for the default domain of the layer:" + layer);
         if (layer == ResourceType.EJB)
            aPolicy = SecurityConfiguration.getApplicationPolicy(EJB);
         else if (layer == ResourceType.WEB)
            aPolicy = SecurityConfiguration.getApplicationPolicy(WEB);
      }
      if (aPolicy == null)
         throw new IllegalStateException(ErrorCodes.NULL_VALUE + "Application Policy is null for domain:" + domainName);

      AuthorizationInfo ai = aPolicy.getAuthorizationInfo();
      if (ai == null)
         return getAuthorizationInfo(layer);
      else
         return aPolicy.getAuthorizationInfo();
   }

   private AuthorizationInfo getAuthorizationInfo(ResourceType layer)
   {
      AuthorizationInfo ai = null;

      if (layer == ResourceType.EJB)
         ai = SecurityConfiguration.getApplicationPolicy(EJB).getAuthorizationInfo();
      else if (layer == ResourceType.WEB)
         ai = SecurityConfiguration.getApplicationPolicy(WEB).getAuthorizationInfo();
      else
      {
         if (log.isTraceEnabled())
            log.trace("AuthorizationInfo not found. Providing default authorization info");
         ai = new AuthorizationInfo(SecurityConstants.DEFAULT_APPLICATION_POLICY);
         ai.add(new AuthorizationModuleEntry(DelegatingAuthorizationModule.class.getName()));
      }
      return ai;
   }

   private String getAdditionalErrorMessage(Exception e)
   {
      StringBuilder msg = new StringBuilder(" ");
      if (e != null)
         msg.append(e.getLocalizedMessage());
      return msg.toString();
   } 
}