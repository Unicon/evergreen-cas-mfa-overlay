package net.unicon.cas.mfa.web.flow;

import java.util.Map;

import net.unicon.cas.addons.authentication.AuthenticationSupport;
import net.unicon.cas.mfa.web.flow.util.MultiFactorRequestContextUtils;
import net.unicon.cas.mfa.web.support.MultiFactorAuthenticationSupportingWebApplicationService;

import org.jasig.cas.authentication.Authentication;
import org.jasig.cas.authentication.principal.Service;
import org.jasig.cas.authentication.principal.WebApplicationService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.webflow.core.collection.MutableAttributeMap;
import org.springframework.webflow.core.collection.ParameterMap;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(JUnit4.class)
public class ValidateInitialMultiFactorAuthenticationRequestActionTests {

    private static final String TGT_ID = "TGT-1";

    private ValidateInitialMultiFactorAuthenticationRequestAction action;

    @Mock
    private RequestContext requestContext;

    @Mock
    private Authentication authentication;

    public ValidateInitialMultiFactorAuthenticationRequestActionTests() {
        MockitoAnnotations.initMocks(this);
    }

    @Before
    public void setup() {
        final AuthenticationSupport support = mock(AuthenticationSupport.class);
        when(support.getAuthenticationFrom(TGT_ID)).thenReturn(authentication);

        this.action = new ValidateInitialMultiFactorAuthenticationRequestAction(support);

        final MutableAttributeMap flowScope = mock(MutableAttributeMap.class);
        when(requestContext.getFlowScope()).thenReturn(flowScope);

        final ParameterMap requestParams = mock(ParameterMap.class);
        when(requestContext.getRequestParameters()).thenReturn(requestParams);

    }

    private void setMockTgtContextWith(final String tgt) {
        when(requestContext.getFlowScope().get(MultiFactorRequestContextUtils.CAS_TICKET_GRANTING_TICKET_ATTR_NAME)).thenReturn(tgt);
    }

    private void setMockServiceContextWith(final Service svc) {
        when(requestContext.getFlowScope().get("service")).thenReturn(svc);
    }

    @Test
    public void testMissingService() throws Exception {
        setMockTgtContextWith(TGT_ID);
        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_TGT, ev.getId());
    }

    @Test
    public void testMissingTgtId() throws Exception {
        setMockTgtContextWith(null);
        setMockServiceContextWith(mock(MultiFactorAuthenticationSupportingWebApplicationService.class));

        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_TGT, ev.getId());
    }

    @Test
    public void testWrongServiceType() throws Exception {
        setMockTgtContextWith(TGT_ID);
        setMockServiceContextWith(mock(WebApplicationService.class));

        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_TGT, ev.getId());
    }

    @Test
    public void testValidRequest() throws Exception {
        setMockTgtContextWith(TGT_ID);
        setMockServiceContextWith(mock(MultiFactorAuthenticationSupportingWebApplicationService.class));
        when(
                this.requestContext.getRequestParameters().get(
                        MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD)).thenReturn(
                MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD);

        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_MFA, ev.getId());
    }

    @Test
    public void testValidRequestWithMfa() throws Exception {
        final String AUTHN_METHOD = "strong_two_factor";

        setMockTgtContextWith(TGT_ID);
        final MultiFactorAuthenticationSupportingWebApplicationService mfaSvc =
                mock(MultiFactorAuthenticationSupportingWebApplicationService.class);

        when(mfaSvc.getAuthenticationMethod()).thenReturn(AUTHN_METHOD);
        setMockServiceContextWith(mfaSvc);

        when(
                this.requestContext.getRequestParameters().get(
                        MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD))
                .thenReturn(AUTHN_METHOD);

        final Map map = mock(Map.class);
        when(map.get(MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD))
            .thenReturn(AUTHN_METHOD);

        when(authentication.getAttributes()).thenReturn(map);

        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_TGT, ev.getId());
    }

    @Test
    public void testValidRequestWithMfaUnknownAuthnMethod() throws Exception {
        final String AUTHN_METHOD = "strong_two_factor";

        setMockTgtContextWith(TGT_ID);
        final MultiFactorAuthenticationSupportingWebApplicationService mfaSvc =
                mock(MultiFactorAuthenticationSupportingWebApplicationService.class);

        when(mfaSvc.getAuthenticationMethod()).thenReturn(AUTHN_METHOD);
        setMockServiceContextWith(mfaSvc);

        when(
                this.requestContext.getRequestParameters().get(
                        MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD))
                .thenReturn("unknown_authn_method");

        final Map map = mock(Map.class);
        when(map.get(MultiFactorAuthenticationSupportingWebApplicationService.CONST_PARAM_AUTHN_METHOD))
            .thenReturn(AUTHN_METHOD);

        when(authentication.getAttributes()).thenReturn(map);

        final Event ev = this.action.doExecute(this.requestContext);
        assertNotNull(ev);
        assertEquals(ValidateInitialMultiFactorAuthenticationRequestAction.EVENT_ID_REQUIRE_TGT, ev.getId());
    }
}
