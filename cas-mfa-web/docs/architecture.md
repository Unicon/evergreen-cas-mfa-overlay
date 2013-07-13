WORK IN PROGRESS.  NOT YET COMPLETE.

# Target audience

The target audience for this page is CAS community members seeking to understand the architecture and makeup of this CAS server extension.

This is not the guide for locally implementing.


# Scope

This document narrates the architecture of the customizations in this project.

This document lives in `cas-mfa-web` because its scope is the customizations the web components depend upon and the web components themselves.

# The Big Picture

Whereas this project is about enabling multifactor authentication, the architecture and customizations are more about enabling and enforcing services opting into alternative branches of the CAS login web flow than particularly about multifactor authentication as such.

CAS-using applications supply an authentication method hint on their redirect to CAS login as an `authn_method` request parameter, akin to the `renew` and `gateway` parameters traditionally available in CAS.

CAS remenbers how the user authenticated.

CAS includes the authentication mechanism in a customized ticket validation response and enforces a parameter-specified authentication method on ticket validation.

# A components analysis

One way to understand this project is to review the components and how they interact, how control flows through them.  This section reviews in this way.


## Detecting CAS-using services requiring alternative authentication mechanisms

CAS-using services indicate to CAS that they require an alternative login flow via an additional `authn_method` request parameter on login.

In CAS server architecture terms, the parameter on login is an "argument" to CAS that needs to be "extracted" from the request.  The CAS server normally uses a `CasArgumentExtractor` to extract arguments afforded by the traditional CAS protocol.  This project adds a `MultiFactorAuthenticationArgumentExtractor` (declared in  `argumentExtractorsConfiguration.xml` ) to extract the optional directed authentication method parameter and constrain it to the set of known supported authentication method names.

The fundamental purpose of argument extractors are to generate Service objects representing the CAS-using service the end user is trying to log in to.  The `MultiFactorAuthenticationArgumentExtractor` generates a `MultiFactorAuthenticationSupportingWebApplicationService` .  These are like regular CAS `WebApplicationService`s except they know what authentication method the service has required.

## Honoring alternative authentication requirements in the login experience

### mfaTicketGrantingTicketExistsCheck login flow state

The `login-webflow.xml` is customized to start with a new inserted action state, namely,

    <action-state id="mfaTicketGrantingTicketExistsCheck">
        <evaluate expression="validateInitialMfaRequestAction" />
        <transition on="requireMfa" to="multiFactorAuthentication" />
        <transition on="requireTgt" to="ticketGrantingTicketExistsCheck" />
    </action-state>

The `validateInitialMfaRequestAction` is defined in `mfa-servlet.xml` as

    <bean id="validateInitialMfaRequestAction"
        class="net.unicon.cas.mfa.web.flow.ValidateInitialMultiFactorAuthenticationRequestAction"
        c:authSupport-ref="authenticationSupport" />


This custom action considers the authentication method required by the CAS-using service the user is attempting to log in to, if any, and compares this to the recorded method of how the user authenticated previously in the active single sign-on session, if any, and makes a binary decision about whether the login flow should proceeed as per normal (the `requireTgt` event) or whether the flow should branch (the `requireMfa` event) to enforce an as-yet unfulfilled authentication method requirement expressed by the CAS-using service.

This Action branches to `requireMfa` only if the user has an existing single sign-on session that does not yet meet the authentication method requirements.  If the requirements are already met *or if the user hasn't logged in at all* the Action signals to proceed the flow.  This is so that the user will first complete the normal user and password login process before (later, through another flow action) branching to then experience and fulfill the additional authentication factor requirement.

### multiFactorAuthentication login flow state

In the `requireMfa` case, the flow proceeds to the `multiFactorAuthentication` state.  This is a Spring Web Flow subflow-state:

    <subflow-state id="multiFactorAuthentication" subflow="mfa">
        <on-entry>
            <evaluate expression="generateMfaCredentialsAction.createCredentials(flowRequestContext, credentials, credentials.username)"/>
        </on-entry>

        <input name="mfaCredentials" value="flowScope.mfaCredentials" required="true"
               type="net.unicon.cas.mfa.authentication.principal.MultiFactorCredentials" />
        <transition on="mfaSuccess" to="sendTicketGrantingTicket" />
        <transition on="mfaError" to="generateLoginTicket" />
    </subflow-state>

On entering the state, the flow invokes `generateMfaCredentialsAction.createCredentials()`.

`generateMfaCredentialsAction` is defined in `mfa-servlet.xml`

    <!-- Generate and chain multifactor credentials based on current authenticated credentials. -->
    <bean id="generateMfaCredentialsAction" class="net.unicon.cas.mfa.web.flow.GenerateMultiFactorCredentialsAction"
        p:authenticationSupport-ref="authenticationSupport"/>

This simply reads or instantiates a MultiFactoCredentials instance to back the one-time-password form.

This is a sub-flow action with `subflow="mfa"`, so it branches control to the `mfa-webflow.xml` subflow.

### mfa-webflow.xml subflow

This sub-flow is a typical Spring Web Flow rendering and handling the submission of a form, here to collect the additional one-time password credentials making up the additional authentication factor to achieve multi (two) factor authentication.

It uses the `loginTicket` technique of the typical CAS username and password login form to discourage form replay and it follows the standard CAS login flow pattern separating the form rendering and submit-processing flow states.

The additional credential is currently modeled as an additional username and password credential.

Submitting the form actuates `terminatingTwoFactorAuthenticationViaFormAction.doBind(flowRequestContext, flowScope.credentials)`

Like other flow actions, this is defined in `mfa-servlet.xml`:

    <bean id="terminatingTwoFactorAuthenticationViaFormAction"
        class="net.unicon.cas.mfa.web.flow.TerminatingMultiFactorAuthenticationViaFormAction"
        p:centralAuthenticationService-ref="mfaAwareCentralAuthenticationService"
        p:multiFactorAuthenticationManager-ref="terminatingAuthenticationManager" />

`TerminatingMultiFactorAuthenticationViaFormAction` `doBind()` "binds" the submitted form to CAS concepts of Credentials.

Subsequently, `submit()` actually authenticates the credentials.

The flow can end in successful authentication of the additional credential or in error.

### Returning from the sub-flow

Back in the main login-webflow, completing the mfa subflow yields

    <subflow-state id="multiFactorAuthentication" subflow="mfa">
        <on-entry>
            <evaluate expression="generateMfaCredentialsAction.createCredentials(flowRequestContext, credentials, credentials.username)"/>
        </on-entry>

        <input name="mfaCredentials" value="flowScope.mfaCredentials" required="true"
               type="net.unicon.cas.mfa.authentication.principal.MultiFactorCredentials" />
        <transition on="mfaSuccess" to="sendTicketGrantingTicket" />
        <transition on="mfaError" to="generateLoginTicket" />
    </subflow-state>


Success in fulfilling the authentication method requirement modeled in the sub-flow leads to sending the ticket granting ticket down to the browser.

Error in the sub-flow sends the user back to the generate login ticket step in the flow.


### Branching to multi-factor authentication after traditional authentication

Recall that back in `mfaTicketGrantingTicketExistsCheck` there were two options: `requireMfa` and `requireTgt`.  The above treated in some detail the `requireMfa` case arising when an existing single sign-on session is insufficient.  Howver, the `requireTgt` normal login flow path proceeded in *both* the case where no particular unfulfilled authentication method is required *and* in the case where a particular authentication method is required but the branching needs deferred to later after the traditional login form.

Therefore, the processing of the regular username and password login form needs to include an additional check after the form to determine if branching to the sub-flow is then appropriate.

This happens in the main login flow's `realSubmit`

    <action-state id="realSubmit">
      <evaluate expression="initiatingAuthenticationViaFormAction.submit(flowRequestContext,
         flowScope.credentials, messageContext, flowScope.credentials.username)" />
         <transition on="warn" to="warn" />
         <transition on="success" to="sendTicketGrantingTicket" />
         <transition on="error" to="generateLoginTicket" />
         <transition on="mfaSuccess" to="multiFactorAuthentication" />
    	</action-state>

Note that `mfaSuccess` leads to that same `multiFactorAuthentication` sub-flow.


## Remembering how users authenticated

In order to appropriately handle existing sessions and in order to be able to include the authentication method in the validation response, the CAS server needs to remember how the user authenticated.

This is implemented as metadata on the Authentication.

The Terminating... Action chains the new Multifactor authentication onto the prior Authentication and packages this into a new Ticket Granting Ticket.

    private Event createTicketGrantingTicket(final Authentication authentication, final RequestContext context,
            final Credentials credentials, final MessageContext messageContext, final String id) {
        ...
            final MultiFactorCredentials mfa = MultiFactorRequestContextUtils.getMfaCredentials(context);

            mfa.getChainedAuthentications().add(authentication);
            mfa.getChainedCredentials().put(id, credentials);

            MultiFactorRequestContextUtils.setMfaCredentials(context, mfa);
            WebUtils.putTicketGrantingTicketInRequestScope(context, this.cas.createTicketGrantingTicket(mfa));
            return getSuccessEvent();
        ...
    }



## Including Authentication Method in the Validation Response and Enforcing

This project customizes the traditional CAS server XML ticket validation responses to include the authentication method.  This allows CAS-using sevices to predicate behavior on or apply access control rules on how the user authenticated.

CAS-using applications can also communicate their authentication method requirement to the CAS server on their ticket validation request and rely on the CAS server to enforce the expressed authentication method requirements.

### Including the authentication method in the ticket validation response

The `casServiceValidate.jsp` is customized to include

        <c:if test="${not empty authn_method}">
          <cas:authn_method>${authn_method}</cas:authn_method>
        </c:if>
      </c:if>
    </cas:authenticationSuccess>

A customized `MultiFactorServiceValidateController` places the `authn_method` attribute into the model available to the JSP for rendering:

    ...
    final int index = assertion.getChainedAuthentications().size() - 1;
    final Authentication authToUse = assertion.getChainedAuthentications().get(index);

    final String authnMethodUsed = (String) authToUse.getAttributes()
      .get(MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD);
    if (authnMethodUsed != null) {
      success.addObject(MODEL_AUTHN_METHOD, authnMethodUsed);
    }
    ...



### Enforcing a CAS-using-service-indicated required authentication method

In CAS, criteria as to whether a given ticket meets a given service's validation requirements are modeled as Specifications.

Traditionally, CAS-using applications can specifiy "renew=true" on a validation request, which causes the Specification to require that the ticket include a fresh underlying Authentication.  Likewise, CAS-using applications can and should use the `/serviceValidate` rather than `/proxyValidate` ticket validation endpoint whenever possible, with the `/serviceValidate` endpoint rejecting all proxy tickets.  That `/serviceValidate` requirement against proxy chains is also modeled as a Specification.

A service's epxressed requirement for a particular authentication method is likewise modeled as a Specification, with the customized MultiFactorServiceValidateController manually binding the "authn_method" request parameter into a `MultiFactorAuthenticationProtocolValidationSpecification` and then asking that specification whether the available Assertion fulfills it.

The Specifiation implementation is a little goofy in using runtime exceptions to characterize how the assertion does not match since the API would have afforded only true or false.




### `/proxyValidate` unmofified

These customizations do not modify proxy ticket validation.

TODO: modify proxy ticket validation



# Functional analysis

Another way to think through this code is to consider the use cases and how they are implemented.

## Service specifies no particular authentication method

## Service specifies an authentication method; new session

## Service specifies an authentication method; existing sufficient session

## Service specifies an authentication method; existing insufficient session

## Validation doesn't match login




# Guidance for extension

## Adding an additional authentication method

TODO


## Modeling service authentication method requirements in the services registry rather than as a request parameter

TODO