/**
 * Copyright (c) Inalogy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.inalogy.midpoint.connectors.freeipa;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.*;
import java.net.URISyntaxException;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributeInfos;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateDeltaOp;
import org.json.JSONArray;
import org.json.JSONObject;

import com.evolveum.polygon.rest.AbstractRestConnector;

/**
 * @author gpalos
 *
 */
@ConnectorClass(displayNameKey = "freeipa.connector.display", configurationClass = FreeIpaConfiguration.class)
public class FreeIpaConnector extends AbstractRestConnector<FreeIpaConfiguration> implements TestOp, SchemaOp, CreateOp, UpdateDeltaOp, DeleteOp, SearchOp<FreeIpaFilter>  {

	private static final Log LOG = Log.getLog(FreeIpaConnector.class);

	private static final String API_VERSION = "2.117";

	public static final String OBJECT_CLASS_USER = "user";
	public static final String OBJECT_CLASS_GROUP = "group";
	public static final String OBJECT_CLASS_ROLE = "role";
	public static final String OBJECT_CLASS_HOSTGROUP = "hostgroup";
	public static final String OBJECT_CLASS_HBACRULE = "hbacrule";
	public static final String OBJECT_CLASS_HOST = "host";

	private static final String[] CLASS_NAMES = {OBJECT_CLASS_USER, OBJECT_CLASS_GROUP, OBJECT_CLASS_ROLE,
			OBJECT_CLASS_HOSTGROUP, OBJECT_CLASS_HBACRULE, OBJECT_CLASS_HOST};

	/**
	 * Object classes where FreeIPA's introspected schema declares types that do not match
	 * what the JSON-RPC layer actually sends. Their attributes are still enumerated from
	 * introspection - only the declared type is ignored, leaving them untyped. See the
	 * comment in buildObjectClass() for what specifically breaks.
	 */
	private static final List<String> UNTRUSTED_TYPE_CLASSES = Arrays.asList(OBJECT_CLASS_HOSTGROUP, OBJECT_CLASS_HBACRULE);

	public static final String ATTR_CN = "cn";
	public static final String ATTR_UID = "uid";
	public static final String ATTR_DN = "dn";
	public static final String ATTR_GIVENNAME = "givenname";
	public static final String ATTR_SN = "sn";
	public static final String ATTR_USERPASSWORD = "userpassword";
	public static final String ATTR_RENAME = "rename";
	public static final String ATTR_NSACCOUNTLOCK = "nsaccountlock";
	public static final String ATTR_MEMBEROF_GROUP = "memberof_group";
	public static final String ATTR_MEMBEROF_ROLE = "memberof_role";
	public static final String ATTR_MEMBER_USER = "member_user";
	public static final String ATTR_DESCRIPTION = "description";
	public static final String ATTR_PHYSICALDELIVERYOFFICENAME = "physicaldeliveryofficename";
	public static final String ATTR_KRBPASSWORDEXPIRATION = "krbpasswordexpiration";

	public static final String ATTR_IPAUNIQUEID = "ipauniqueid";
	public static final String ATTR_MEPMANAGEDENTRY = "mepmanagedentry";
	public static final String ATTR_OBJECTCLASS = "objectclass";
	public static final String ATTR_KRBLOGINFAILEDCOUNT = "krbloginfailedcount";
	public static final String ATTR_KRBEXTRADATA = "krbextradata";
	public static final String ATTR_KRBLASTPWDCHANGE = "krblastpwdchange";
	public static final String ATTR_KRBLASTFAILEDAUTH = "krblastfailedauth";
	public static final String ATTR_IPANTSECURITYIDENTIFIER = "ipantsecurityidentifier";
	public static final String ATTR_KRBTICKETFLAGS = "krbticketflags";
	public static final String ATTR_KRBLASTADMINUNLOCK = "krblastadminunlock";
	public static final String ATTR_KRBPWDPOLICYREFERENCE = "krbpwdpolicyreference";

	public static final String ATTR_IPANTHASH = "ipanthash"; // freeradius
	public static final String ATTR_IPANTHOMEDIRECTORYDRIVE = "ipanthomedirectorydrive";

    public static String COOKIE_VALUE = null;

	public static final String ATTR_NOPRIVATE = "noprivate";
	public static final String ATTR_GIDNUMBER = "gidnumber";

	// host
	/** Primary key of the host object class - hosts are keyed on fqdn, not cn. */
	public static final String ATTR_FQDN = "fqdn";
	/**
	 * host_add refuses a name with no DNS A record unless force is set. Host entries here are
	 * provisioned from VM inventory, where DNS frequently lags or does not exist at all, so
	 * the option is always sent. FreeIPA still requires a fully-qualified name.
	 */
	public static final String ATTR_FORCE = "force";

	// hostgroup / hbacrule
	public static final String ATTR_MEMBERHOST_HOST = "memberhost_host";
	public static final String ATTR_MEMBERUSER_USER = "memberuser_user";
	public static final String ATTR_MEMBER_HOST = "member_host";
	public static final String ATTR_IPAENABLEDFLAG = "ipaenabledflag";
	public static final String ATTR_ACCESSRULETYPE = "accessruletype";
	public static final String ATTR_USERCATEGORY = "usercategory";
	public static final String ATTR_HOSTCATEGORY = "hostcategory";
	public static final String ATTR_SERVICECATEGORY = "servicecategory";

	/**
	 * Attributes FreeIPA computes and returns but never accepts as input. Its introspected
	 * schema omits the "required" flag for them, which - given the default below - would
	 * otherwise surface them to midPoint as mandatory. Verified on FreeIPA 4.12.2: user
	 * objects return has_password/has_keytab as booleans, group objects return
	 * membermanager_* as read-only membership lists.
	 */
	private static final List<String> READ_ONLY_COMPUTED = Arrays.asList("has_password", "has_keytab");

	/**
	 * Attribute-name prefixes that denote a membership list. Kept in sync with the
	 * multi-value widening in buildObjectClass() - these are the prefixes that occur in
	 * FreeIPA's introspected schema for user/group/role. The hbacrule/hostgroup membership
	 * attributes (memberuser_*, memberhost_*, memberservice_*, sourcehost_*) are declared
	 * multi-valued explicitly in buildStaticObjectClass().
	 */
	private static final List<String> MEMBERSHIP_PREFIXES = Arrays.asList(
			// subject-side projections, present on user / group / role
			"memberof_", "member_", "memberindirect_", "memberofindirect_", "membermanager_",
			// object-side lists, present on hostgroup / hbacrule. FreeIPA reports several of
			// these as single-valued even though every one of them is a list, so covering
			// them by prefix fixes the cardinality for any FreeIPA version rather than
			// relying on a hand-maintained per-attribute list.
			"memberuser_", "memberhost_", "memberservice_", "sourcehost_",
			// host: managedby_host is a multi-valued list maintained with
			// host_add_managedby / host_remove_managedby, never through host_mod. Treating it
			// as a membership projection makes it multi-valued, optional, and skipped on
			// write - sending it to host_mod would draw FreeIPA error 3005, unknown option.
			"managedby_");

	private static boolean isMembershipAttribute(String attributeName) {
		if (attributeName == null) {
			return false;
		}
		for (String prefix : MEMBERSHIP_PREFIXES) {
			if (attributeName.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}


	// alternative config because MID-5883
	private static final boolean DISABLE_ADMINISTRATIVE_STATUS = true; // workaround



	private static final List<String> USER_MULTIVALUED = Arrays.asList("krbprincipalname", "mail", "telephonenumber", "mobile", "pager", "facsimiletelephonenumber",
			"carlicense", "ipasshpubkey", "ipauserauthtype", "userclass", "departmentnumber", "usercertificate", /*"noprivate", */"no_members");


	// Free IPA schema cache
	private static JSONObject schema = null;

	// this should probably be done in a better way
	private static Set<String> preserved_user_list = new HashSet<>();

    @Override
    public void init(Configuration configuration) {
        LOG.info("Initializing {0} connector instance {1}", this.getClass().getSimpleName(), this);
    	super.init(configuration);
        // log in as post over HTTPS, Password Authentication see: https://vda.li/en/posts/2015/05/28/talking-to-freeipa-api-with-sessions/
    }

    @Override
    public CloseableHttpResponse execute(HttpUriRequest request) {
        try{
            BasicCookieStore cookieStore = new BasicCookieStore();
            BasicClientCookie cookie = new BasicClientCookie("ipa_session", COOKIE_VALUE);
            URI uri = new URI(getConfiguration().getServiceAddress());
            cookie.setDomain(uri.getHost());
            cookie.setPath(uri.getPath());
            cookieStore.addCookie(cookie);

            HttpClientContext context = HttpClientContext.create();
            context.setAttribute(HttpClientContext.COOKIE_STORE, cookieStore);

            return getHttpClient().execute(request,context);
        } catch (IOException e) {
            throw new ConnectorIOException(e.getMessage(), e);
        } catch (URISyntaxException e){
            throw new ConnectorException(e.getMessage(), e);
        }
    }

    protected boolean login() {
        boolean result = false;

        // log in as post over HTTPS, Password Authentication see: https://vda.li/en/posts/2015/05/28/talking-to-freeipa-api-with-sessions/
        CloseableHttpClient client = getHttpClient();

        final List<String> passwordList = new ArrayList<String>(1);
        GuardedString guardedPassword = getConfiguration().getPassword();

        if (guardedPassword != null) {
            guardedPassword.access(new GuardedString.Accessor() {
                @Override
                public void access(char[] chars) {
                    passwordList.add(new String(chars));
                }
            });
        }
        String password = null;
        if (!passwordList.isEmpty()) {
            password = passwordList.get(0);
        }

        // log in
        HttpPost httpPost = new HttpPost(getConfiguration().getServiceAddress()+"/session/login_password");

		// this header is required for login from version 4.9.12
		httpPost.setHeader("Referer", getConfiguration().getServiceAddress());

        HttpClientContext context = HttpClientContext.create();

        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("user", getConfiguration().getUsername()));
        params.add(new BasicNameValuePair("password", password));
        CloseableHttpResponse response;
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(params));
            response = client.execute(httpPost,context);
            COOKIE_VALUE=context.getCookieStore().getCookies().get(0).getValue();
            result=true;
            LOG.info("Connector instance created, authnetication result is {0}", response);
        } catch (UnsupportedEncodingException e) {
            LOG.error("cannot log in to freeIPA: " + e, e);
            throw new ConnectorIOException(e.getMessage(), e);
        } catch (IOException e) {
            LOG.error("cannot log in to freeIPA: " + e, e);
            throw new ConnectorIOException(e.getMessage(), e);
        }
        return result;
    }

    @Override
    public void dispose() {
        super.dispose();
        schema = null;
    }

//    @Override
//    public void checkAlive() {
//        test();
//        // TODO quicker test?
//    }

	@Override
	public void test() {
		// test connection
		JSONObject resp = callRequest(getIpaRequest("ping"));
		LOG.info("Free IPA environment details: \n{0}", resp);

        //client.close();
	}


	@Override
	public Schema schema() {
		SchemaBuilder schemaBuilder = new SchemaBuilder(FreeIpaConnector.class);

		if (schema==null) {
			schema = callRequest(getIpaRequest("schema"));
		}

		for (String className : CLASS_NAMES) {
	        buildObjectClass(schemaBuilder, className);
		}

        return schemaBuilder.build();
	}



	private void buildObjectClass(SchemaBuilder schemaBuilder, String className) {
		ObjectClassInfoBuilder objClassBuilder = new ObjectClassInfoBuilder();
		objClassBuilder.setType(className);

        // UID & NAME are defaults
		// Tracks what has already been declared, so the introspected schema, the
		// per-class workarounds below and buildStaticObjectClass() can be layered without
		// declaring the same attribute twice. First writer wins, so FreeIPA's own
		// definition takes precedence over our static fallback.
		Set<String> declaredAttrs = new HashSet<>();

		// Every object class is enumerated from FreeIPA's own introspected schema, because
		// only FreeIPA knows what a given deployment returns - version, installed plugins and
		// local schema extensions all change the attribute set. Hand-maintaining the list
		// instead risks omitting an attribute that some deployment does return, and an
		// attribute midPoint has no definition for makes it recurse until it throws
		// StackOverflowError rather than reporting a clear error (see the note in
		// convertKeyedToConnectorObject).
		//
		// What cannot be trusted is FreeIPA's *typing* for hostgroup and hbacrule: it declares
		// ipaenabledflag as "bool" while hbacrule_show answers with the string "TRUE", so
		// honouring that type makes midPoint reject every rule with
		//   "The value 'PPV(String:true)' does not conform to the definition ... boolean"
		// and takes down HBAC reconciliation entirely. For those classes the declared type is
		// therefore ignored and every attribute is left untyped (xsd:string), which matches
		// what the JSON-RPC layer really sends. Cardinality is handled separately, by prefix,
		// in isMembershipAttribute().
		boolean distrustDeclaredTypes = UNTRUSTED_TYPE_CLASSES.contains(className);
		JSONArray classes = FreeIpaConnector.schema.getJSONObject("result").getJSONObject("result").getJSONArray("classes");
		boolean ipaNtHomeDirectoryAlreadyFound = false;
		for (int i = 0; i < classes.length(); ++i) {
		    JSONObject jsonClass = classes.getJSONObject(i);
		    String jsonClassName = jsonClass.getString("name");
		    if (jsonClassName.equals(className)) {
		    	JSONArray jsonParams = jsonClass.getJSONArray("params");
		    	for (int p = 0; p < jsonParams.length(); ++p) {
		    		JSONObject jsonParam = jsonParams.getJSONObject(p);

		    		Boolean required = jsonParam.has("required") ? jsonParam.getBoolean("required") : true; //default is true
		    		String flagName = jsonParam.optString("name", "");
		    		if (isMembershipAttribute(flagName) || READ_ONLY_COMPUTED.contains(flagName)) {
		    			// FreeIPA omits "required" for computed and membership params, so the
		    			// default above would surface them to midPoint as mandatory. FreeIPA
		    			// never accepts them as input (verified 4.12.2: has_password,
		    			// has_keytab, membermanager_user, membermanager_group).
		    			required = false;
		    		}
		    		// optString: a parameter missing "type" must not abort the whole schema.
		    		String type = jsonParam.optString("type", "str"); // Principal, datetime, Certificate, ... str, bool, int

		    		Boolean multivalue = jsonParam.has("multivalue") ? jsonParam.getBoolean("multivalue") : false; //default is false
		    		String attributeName = jsonParam.optString("name", null);
		    		if (attributeName == null || attributeName.isEmpty()) {
		    			// A nameless parameter cannot be declared; skipping it is better than
		    			// losing the entire schema to a JSONException.
		    			LOG.warn("Skipping schema parameter without a name in object class {0}", className);
		    			continue;
		    		}
		    		if (!declaredAttrs.add(attributeName)) {
		    			LOG.ok("Attribute {0} already declared for object class {1}, skipping duplicate", attributeName, className);
		    			continue;
		    		}
		    		AttributeInfoBuilder attrBuilder = new AttributeInfoBuilder(attributeName);
		    		if ("bool".equals(type) && !distrustDeclaredTypes)
		    			attrBuilder.setType(Boolean.class);
	//		    		else if ("int".equals(type)) // uidnumber, gidnumber problem
	//		    			attrBuilder.setType(Integer.class);

		    		if (required)
		    			attrBuilder.setRequired(true);
		    		// Schema fix: FreeIPA's introspected schema reports membership attributes as
		    		// single-valued, but every one of them is a list - a user belongs to many
		    		// groups/roles/sudorules/hbacrules, an HBAC rule has many member users and
		    		// hosts. Decided by prefix in isMembershipAttribute(), which is the single
		    		// source of truth for this: the same predicate also drives the required-flag
		    		// correction above and the write-skipping in createKeyed(), so a prefix added
		    		// there takes effect everywhere rather than in one of three places.
		    		if (multivalue || isMembershipAttribute(attributeName))
		    			attrBuilder.setMultiValued(true);

		            objClassBuilder.addAttributeInfo(attrBuilder.build());
		            if (ATTR_IPANTHOMEDIRECTORYDRIVE.equals(attributeName))
						ipaNtHomeDirectoryAlreadyFound = true;
		    	}
		    }
		}


		// missing from FreeIPA's introspected schema (workaround). Routed through
		// addStaticAttr so it is skipped if FreeIPA did report it - declaring the same
		// attribute twice makes ObjectClassInfoBuilder throw.
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_OBJECTCLASS, true);


		if (OBJECT_CLASS_USER.equals(className)) {
			if (!DISABLE_ADMINISTRATIVE_STATUS) {
				objClassBuilder.addAttributeInfo(OperationalAttributeInfos.ENABLE);     // status
			}

			AttributeInfoBuilder attrIpaUniqueIdBuilder = new AttributeInfoBuilder(ATTR_IPAUNIQUEID); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrIpaUniqueIdBuilder.build());

			AttributeInfoBuilder attrMepManagedEntryBuilder = new AttributeInfoBuilder(ATTR_MEPMANAGEDENTRY); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrMepManagedEntryBuilder.build());

			AttributeInfoBuilder attrKrbLoginFailedCountBuilder = new AttributeInfoBuilder(ATTR_KRBLOGINFAILEDCOUNT); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbLoginFailedCountBuilder.build());

			AttributeInfoBuilder attrKrbExtraDataBuilder = new AttributeInfoBuilder(ATTR_KRBEXTRADATA); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbExtraDataBuilder.build());

			AttributeInfoBuilder attrKrbLastPwdChangeBuilder = new AttributeInfoBuilder(ATTR_KRBLASTPWDCHANGE); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbLastPwdChangeBuilder.build());

			AttributeInfoBuilder attrKrbLastFailedAuthBuilder = new AttributeInfoBuilder(ATTR_KRBLASTFAILEDAUTH); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbLastFailedAuthBuilder.build());

			AttributeInfoBuilder attrDnBuilder = new AttributeInfoBuilder(ATTR_DN); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrDnBuilder.build());

			AttributeInfoBuilder attrIpaNtSecurityIdentifierBuilder = new AttributeInfoBuilder(ATTR_IPANTSECURITYIDENTIFIER); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrIpaNtSecurityIdentifierBuilder.build());

			AttributeInfoBuilder attrKrbTicketFlagsBuilder = new AttributeInfoBuilder(ATTR_KRBTICKETFLAGS); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbTicketFlagsBuilder.build());

			AttributeInfoBuilder attrKrbLastAdminUnlockBuilder = new AttributeInfoBuilder(ATTR_KRBLASTADMINUNLOCK); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrKrbLastAdminUnlockBuilder.build());

	        AttributeInfoBuilder attrNoPrivateBuilder = new AttributeInfoBuilder(ATTR_NOPRIVATE); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrNoPrivateBuilder.build());

			AttributeInfoBuilder attrIpaNtHashBuilder = new AttributeInfoBuilder(ATTR_IPANTHASH); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrIpaNtHashBuilder.build());

	        if (!ipaNtHomeDirectoryAlreadyFound) {
				AttributeInfoBuilder attrIpaNtHomeDirectoryDrive = new AttributeInfoBuilder(ATTR_IPANTHOMEDIRECTORYDRIVE); // missing from schema (workaround)
				objClassBuilder.addAttributeInfo(attrIpaNtHomeDirectoryDrive.build());
			}

			AttributeInfoBuilder attrKrbPwdPolicyReference = new AttributeInfoBuilder(ATTR_KRBPWDPOLICYREFERENCE); // missing from schema (workaround)
			objClassBuilder.addAttributeInfo(attrKrbPwdPolicyReference.build());

			AttributeInfoBuilder attrDescriptionBuilder = new AttributeInfoBuilder(ATTR_DESCRIPTION); // missing from schema (workaround)
			objClassBuilder.addAttributeInfo(attrDescriptionBuilder.build());

			AttributeInfoBuilder attrPhysicalDeliveryOfficeNameBuilder = new AttributeInfoBuilder(ATTR_PHYSICALDELIVERYOFFICENAME); // missing from schema (workaround)
			objClassBuilder.addAttributeInfo(attrPhysicalDeliveryOfficeNameBuilder.build());

		}

		if (OBJECT_CLASS_GROUP.equals(className)) {
			AttributeInfoBuilder attrIpaUniqueIdBuilder = new AttributeInfoBuilder(ATTR_IPAUNIQUEID); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrIpaUniqueIdBuilder.build());

			AttributeInfoBuilder attrIpaNtSecurityIdentifierAuthBuilder = new AttributeInfoBuilder(ATTR_IPANTSECURITYIDENTIFIER); // missing from schema (workaround)
	        objClassBuilder.addAttributeInfo(attrIpaNtSecurityIdentifierAuthBuilder.build());
		}

		if (OBJECT_CLASS_GROUP.equals(className) || OBJECT_CLASS_ROLE.equals(className)
				|| OBJECT_CLASS_HOST.equals(className)) {
			// FreeIPA returns dn for every object it hands back, but its introspected schema
			// declares it only for user - so group_show and role_show deliver an attribute
			// midPoint has no definition for, which surfaces as a reconciliation
			// partial_error rather than a clear message about dn.
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_DN, false);
		}

		if (OBJECT_CLASS_HOST.equals(className)) {
			// host keeps FreeIPA's introspected schema, unlike hostgroup and hbacrule: its
			// attribute set is large and installation-dependent, so hand-enumerating it would
			// risk omitting something host_show returns - and an attribute absent from the
			// schema makes midPoint recurse infinitely rather than error (see §4.3 of
			// CHANGES.md). Introspection declares whatever FreeIPA returns, which is exactly
			// the property needed here.
			//
			// These are declared anyway because host_show is known to return them while
			// introspection does not reliably describe them. addStaticAttr defers to
			// FreeIPA's own definition when there is one, so this only fills gaps.
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_IPAUNIQUEID, false);
			addStaticAttr(objClassBuilder, declaredAttrs, "serverhostname", false);
			addStaticAttr(objClassBuilder, declaredAttrs, "krbcanonicalname", false);
			addStaticAttr(objClassBuilder, declaredAttrs, "krbprincipalname", true);
			addStaticAttr(objClassBuilder, declaredAttrs, "sshpubkeyfp", true);
			addStaticAttr(objClassBuilder, declaredAttrs, "managedby_host", true);

			// Verified missing from FreeIPA's introspected host schema on 4.12.2, while
			// host_show returns all four. The three Kerberos ones are the same attributes
			// upstream already hand-declares for user, so the omission is consistent across
			// object classes rather than specific to host.
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_CN, false);
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_KRBEXTRADATA, false);
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_KRBLASTPWDCHANGE, false);
			addStaticAttr(objClassBuilder, declaredAttrs, ATTR_KRBPWDPOLICYREFERENCE, false);
		}

		if (OBJECT_CLASS_ROLE.equals(className)) {
			// role_show can return memberof_privilege and memberof_permission. Only the
			// former appears in FreeIPA's introspected schema (verified on 4.12.2), so the
			// latter is declared here: an undeclared attribute would make midPoint reject
			// the whole object. 1.2.9.0 instead discarded both values in the converter,
			// which also threw away memberof_privilege.
			addStaticAttr(objClassBuilder, declaredAttrs, "memberof_permission", true);
			addStaticAttr(objClassBuilder, declaredAttrs, "memberof_privilege", true);
		}

		if (UNTRUSTED_TYPE_CLASSES.contains(className)) {
			buildStaticObjectClass(objClassBuilder, className, declaredAttrs);
		}

        schemaBuilder.defineObjectClass(objClassBuilder.build());
	}

	/**
	 * Safety net for hostgroup and hbacrule: declares the attributes their *_show commands
	 * are known to return, for the case where a deployment's introspected schema does not
	 * describe them. Introspection runs first and wins, so on a FreeIPA that reports these
	 * classes properly this method declares nothing at all.
	 *
	 * It exists because an attribute midPoint has no definition for is not a soft failure -
	 * midPoint recurses until it throws StackOverflowError. Names were taken from live
	 * hostgroup_show / hbacrule_show responses on FreeIPA 4.12.2; a deployment that returns
	 * something outside both this list and its own introspected schema would still need it
	 * adding here.
	 */
	private void buildStaticObjectClass(ObjectClassInfoBuilder objClassBuilder, String className, Set<String> declaredAttrs) {
		LOG.ok("Applying static schema supplement for object class {0}", className);

		// Present on both classes.
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_CN, false);
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_DN, false);
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_DESCRIPTION, false);
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_IPAUNIQUEID, false);
		addStaticAttr(objClassBuilder, declaredAttrs, ATTR_OBJECTCLASS, true);

		if (OBJECT_CLASS_HOSTGROUP.equals(className)) {
			// NOTE: mepmanagedentry is deliberately NOT declared here, and is filtered out
			// in convertKeyedToConnectorObject(). See the comment there before adding it.
			for (String attr : new String[] {
					"memberhost", ATTR_MEMBER_HOST, "member_hostgroup",
					"memberindirect_host", "memberindirect_hostgroup",
					"memberof_hostgroup", "memberofindirect_netgroup" }) {
				addStaticAttr(objClassBuilder, declaredAttrs, attr, true);
			}
		}

		if (OBJECT_CLASS_HBACRULE.equals(className)) {
			for (String attr : new String[] {
					ATTR_IPAENABLEDFLAG, ATTR_ACCESSRULETYPE,
					ATTR_USERCATEGORY, ATTR_HOSTCATEGORY, ATTR_SERVICECATEGORY }) {
				addStaticAttr(objClassBuilder, declaredAttrs, attr, false);
			}
			for (String attr : new String[] {
					ATTR_MEMBERUSER_USER, "memberuser_group",
					ATTR_MEMBERHOST_HOST, "memberhost_hostgroup",
					"memberservice_hbacsvc", "memberservice_hbacsvcgroup",
					"sourcehost_host", "sourcehost_hostgroup" }) {
				addStaticAttr(objClassBuilder, declaredAttrs, attr, true);
			}
		}
	}

	private void addStaticAttr(ObjectClassInfoBuilder objClassBuilder, Set<String> declaredAttrs,
			String attributeName, boolean multiValued) {
		if (!declaredAttrs.add(attributeName)) {
			return; // FreeIPA already reported it; keep its definition
		}
		AttributeInfoBuilder attrBuilder = new AttributeInfoBuilder(attributeName);
		if (multiValued) {
			attrBuilder.setMultiValued(true);
		}
		objClassBuilder.addAttributeInfo(attrBuilder.build());
	}


	private JSONObject getIpaRequest(String method) {
		return getIpaRequest(method, new JSONObject(), new JSONArray());
	}

	private JSONObject getIpaRequest(String method, JSONArray params_array) {
		return getIpaRequest(method, new JSONObject(), params_array);
	}

	private JSONObject getIpaRequest(String method, JSONObject params_value, JSONArray params_array) {
		JSONObject jo = new JSONObject();
        jo.put("method", method);

        params_value.put("version", API_VERSION);
        if (method.contains("_find")) {
        	// finding users, roles, groups - workaround for paging (better way not found yet - FreeIPA GUI use sizelimit=0 :( )
			params_value.put("sizelimit", getConfiguration().getSizelimit());
			params_value.put("timelimit", getConfiguration().getTimelimit());
		}

        JSONArray params = new JSONArray();
        params.put(params_array);
        params.put(params_value);

        jo.put("params", params);

        jo.put("id", "0"); //TODO: request ID generation?

        LOG.info("json request: \n{0}", jo.toString());

        return jo;
	}

    protected JSONObject callRequest(JSONObject jo) {
    	HttpPost request = new HttpPost(getConfiguration().getServiceAddress()+"/session/json");
        // FIXME: don't log request here - password field !!!
//        LOG.info("request JSON: \n{0}", jo); //TODO: OK later,..
        request.setHeader("Referer", getConfiguration().getServiceAddress());
        request.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
        request.setHeader("Accept", ContentType.APPLICATION_JSON.getMimeType());

        //authHeader(request);

        StringEntity entity = new StringEntity( jo.toString(), ContentType.APPLICATION_JSON);
        request.setEntity(entity);
        CloseableHttpResponse response = execute(request);
        if (response.getCode()==401){
            LOG.warn("401 - no cookie detected need to connect");
            boolean logged= login();
            if (logged){
                closeResponse(response);
                response = execute(request);
            }
        }
//        LOG.warn("response: \n{0}", response);

        String result;
		try {
			result = processFreeIpaResponseErrors(response);
		} catch (ParseException e) {
			throw new ConnectorIOException("Error parsing response from FreeIPA: "+response, e);
		} catch (IOException e) {
			throw new ConnectorIOException("Error call request from FreeIPA: "+request+", json: "+jo, e);
		}
        LOG.ok("response body: \n{0}", result);
        closeResponse(response);

        return new JSONObject(result);
    }

    private String processFreeIpaResponseErrors(CloseableHttpResponse response) throws ParseException, IOException{
    	// status is 200 in every time :(
//      super.processResponseErrors(response);

    	String result = EntityUtils.toString(response.getEntity());
        LOG.ok("Result body: {0}", result);
        JSONObject jo = new JSONObject(result);

        if (jo.isNull("error")) {
        	// no error :)
        } else {
            JSONObject error = jo.getJSONObject("error");

            String error_name = error.getString("name");
        	String error_message = error.getString("message");

        	// other specific codes...

        	if ("DuplicateEntry".equals(error_name)) {
                closeResponse(response);
                throw new AlreadyExistsException(error_message);
        	}
        	else if ("NotFound".equals(error_name)) {
                closeResponse(response);
                throw new UnknownUidException(error_message);
        	}
        	else if ("EmptyModlist".equals(error_name) || "AlreadyActive".equals(error_name) || "AlreadyInactive".equals(error_name)) {
        		LOG.warn("Ignoring request error message: {0}", result);
        	} else {
            	// general error
                closeResponse(response);
                throw new ConnectorIOException("Error: " + error + " when parsing result: " + result);
        	}
        }
        return result;
    }

	/**
	 * FreeIPA's *_add_member / *_remove_member / hbacrule_add_* commands do NOT report a
	 * rejected member through the top-level "error" key. They answer HTTP 200 with
	 * "error": null and list what they refused under result.failed, e.g.
	 *
	 *   {"result": {"completed": 0,
	 *               "failed": {"memberhost": {"host": [["web01", "no such entry"]],
	 *                                         "hostgroup": []}}}}
	 *
	 * processFreeIpaResponseErrors() only looks at "error", so every such rejection used to
	 * be reported to midPoint as a successful operation - the shadow recorded a member that
	 * FreeIPA never stored. Verified on the dev instance: adding a non-existent host to an
	 * HBAC rule returned success and changed nothing.
	 *
	 * Throws ConnectorException when FreeIPA refused something, apart from the cases where
	 * the requested end state already holds (adding an existing member, removing an absent
	 * one) - midPoint reissues those routinely and they must stay idempotent.
	 */
	private void checkMemberOperationResult(JSONObject response, String command, String target) {
		JSONObject result = response.optJSONObject("result");
		if (result == null) {
			return;
		}
		JSONObject failed = result.optJSONObject("failed");
		if (failed == null) {
			return;
		}

		List<String> problems = new ArrayList<>();
		Iterator<String> categories = failed.keys();
		while (categories.hasNext()) {
			String category = categories.next(); // memberuser, memberhost, member, ...
			Object categoryValue = failed.get(category);
			if (!(categoryValue instanceof JSONObject)) {
				continue;
			}
			JSONObject byType = (JSONObject) categoryValue;
			Iterator<String> types = byType.keys();
			while (types.hasNext()) {
				String type = types.next(); // user, group, host, hostgroup, ...
				Object entriesValue = byType.get(type);
				if (!(entriesValue instanceof JSONArray)) {
					continue;
				}
				JSONArray entries = (JSONArray) entriesValue;
				for (int i = 0; i < entries.length(); i++) {
					Object entry = entries.get(i);
					String value = null;
					String reason = null;
					if (entry instanceof JSONArray) {
						JSONArray pair = (JSONArray) entry; // ["<value>", "<reason>"]
						value = pair.length() > 0 ? String.valueOf(pair.get(0)) : null;
						reason = pair.length() > 1 ? String.valueOf(pair.get(1)) : null;
					} else {
						reason = String.valueOf(entry);
					}
					if (isBenignMemberFailure(command, reason, type, value, target)) {
						LOG.ok("Ignoring idempotent result of {0} for {1} ''{2}'' on ''{3}'': {4}",
								command, type, value, target, reason);
					} else {
						problems.add(type + " '" + value + "': " + reason);
					}
				}
			}
		}

		if (!problems.isEmpty()) {
			throw new ConnectorException("FreeIPA rejected " + command + " on '" + target
					+ "': " + String.join("; ", problems));
		}
	}

	/**
	 * True when FreeIPA's refusal means the requested end state already holds, which midPoint
	 * must see as success because it reissues the same delta routinely.
	 *
	 * Both arms are decided structurally rather than by matching FreeIPA's message text.
	 * FreeIPA localises those messages, so a text match would work only against an
	 * English-language server: on any other locale a redundant add would start failing and
	 * take user provisioning with it.
	 *
	 * A remove that FreeIPA refuses is always benign - the member is not there, which is the
	 * requested outcome. An add is benign only when the member is already present, which is
	 * checked by reading the object back. That read costs a round trip, but only on the error
	 * path, and only for the one case where the answer matters.
	 */
	private boolean isBenignMemberFailure(String command, String reason, String param,
			String value, String target) {
		if (command.contains("_remove_")) {
			return true;
		}
		if (value == null) {
			return false;
		}

		String[] binding = membershipBindingForCommand(command, param);
		if (binding == null) {
			// Nothing to read the current value back from; treat as a real failure rather
			// than guessing.
			LOG.warn("Cannot verify whether ''{0}'' is already a member of ''{1}'': no read "
					+ "binding for {2}/{3}. Reporting the refusal: {4}",
					value, target, command, param, reason);
			return false;
		}

		try {
			boolean present = readCurrentMembers(binding[0], binding[4], target).contains(value);
			if (present) {
				LOG.ok("{0} refused for ''{1}'' on ''{2}'' but it is already a member; treating as a no-op",
						command, value, target);
			}
			return present;
		} catch (RuntimeException e) {
			LOG.warn("Could not verify membership of ''{0}'' on ''{1}'' after {2} was refused: {3}",
					value, target, command, e.getMessage());
			return false;
		}
	}

	/**
	 * Reverse lookup of membershipBinding() from the command and request parameter a failure
	 * came back on, so the current value can be read for the idempotency check above.
	 */
	private static String[] membershipBindingForCommand(String command, String param) {
		if (command == null || param == null) {
			return null;
		}
		for (String objectClassName : new String[] { OBJECT_CLASS_HOSTGROUP, OBJECT_CLASS_HBACRULE }) {
			for (String attributeName : new String[] {
					ATTR_MEMBER_HOST, "member_hostgroup", ATTR_MEMBERUSER_USER, "memberuser_group",
					ATTR_MEMBERHOST_HOST, "memberhost_hostgroup",
					"memberservice_hbacsvc", "memberservice_hbacsvcgroup" }) {
				String[] binding = membershipBinding(objectClassName, attributeName);
				if (binding != null && command.equals(binding[1]) && param.equals(binding[3])) {
					return binding;
				}
			}
		}
		return null;
	}

	@Override
	public FilterTranslator<FreeIpaFilter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
		 return new FreeIpaFilterTranslator();
	}

	/**
	 * Runs a *_show for a single object and returns its result, or null when FreeIPA reports
	 * that it does not exist.
	 *
	 * A search for something absent has to come back empty rather than raise. ConnId reads
	 * UnknownUidException as "the object this operation targets is gone", and midPoint acts
	 * on it: when the connector resolved an entitlement by name in the middle of modifying
	 * an account - a group named in memberof_group, say - a NotFound for the *group* made
	 * midPoint mark the *account's* shadow dead and tombstone it, and the next recompute then
	 * created a duplicate shadow. Verified on midPoint 4.10.2 against FreeIPA 4.12.2.
	 *
	 * UnknownUidException is still correct for get/update/delete by Uid, which is why this
	 * only softens the search path.
	 */
	private JSONObject showOrNull(String command, String key) {
		try {
			return callRequest(getIpaRequest(command, new JSONObject().put("all", true),
					new JSONArray().put(key))).getJSONObject("result").getJSONObject("result");
		} catch (UnknownUidException e) {
			LOG.ok("{0} found no object named ''{1}''; returning an empty search result", command, key);
			return null;
		}
	}

	@Override
	public void executeQuery(ObjectClass objectClass, FreeIpaFilter query, ResultsHandler handler,
			OperationOptions options)
	{
		try {
            LOG.info("executeQuery on {0}, query: {1}, options: {2}", objectClass, query, options);
            String lookupKey = (query == null) ? null : query.getLookupKey();

            if (objectClass.is(OBJECT_CLASS_USER)) {
                //find by Login name (uid)
                if (lookupKey != null) {
                	JSONObject user = showOrNull("user_show", lookupKey);
//                	JSONObject status = callRequest(getIpaRequest("user_status", params));
                	if (user != null) {
                		handler.handle(convertUserToConnectorObject(user));
                	}
                } else {
                	JSONObject users = callRequest(getIpaRequest("user_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = users.getJSONObject("result").getJSONArray("result");
					// added to support preserved accounts
					if (getConfiguration().getSupportPreserved()){
						JSONObject presparams = new JSONObject();
						presparams.put("all", true);
						presparams.put("preserved", true);
						JSONObject preserved_users = callRequest(getIpaRequest("user_find", presparams, new JSONArray()));
						JSONArray preserved_results = preserved_users.getJSONObject("result").getJSONArray("result");
						for (int i = 0; i < preserved_results.length(); ++i) {
							results.put(preserved_results.get(i));
						}
					}

					for (int i = 0; i < results.length(); ++i) {
            		    JSONObject user = results.getJSONObject(i);
                        ConnectorObject connectorObject = convertUserToConnectorObject(user);
                        handler.handle(connectorObject);
            		}
            		if (results.length() == getConfiguration().getSizelimit()){
            			LOG.warn("Sizelimit reached when searching all users, please increase it over resource config...");
					}
                	// TODO: better paging if possible later...
                }
            }
            else if (objectClass.is(OBJECT_CLASS_ROLE)) {
                //find by role name (uid)
                if (lookupKey != null) {
                	JSONObject role = showOrNull("role_show", lookupKey);
                	if (role != null) {
                		handler.handle(convertRoleToConnectorObject(role));
                	}
                } else {
                	JSONObject roles = callRequest(getIpaRequest("role_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = roles.getJSONObject("result").getJSONArray("result");
            		for (int i = 0; i < results.length(); ++i) {
            		    JSONObject role = results.getJSONObject(i);
                        ConnectorObject connectorObject = convertRoleToConnectorObject(role);
                        handler.handle(connectorObject);
            		}
					if (results.length() == getConfiguration().getSizelimit()){
						LOG.warn("Sizelimit reached when searching all roles, please increase it over resource config...");
					}
                	// TODO: better paging if possible later...
                }
            }
            else if (objectClass.is(OBJECT_CLASS_GROUP)) {
                //find by group name (uid)
                if (lookupKey != null) {
                	JSONObject group = showOrNull("group_show", lookupKey);
                	if (group != null) {
                		handler.handle(convertGroupToConnectorObject(group));
                	}
                } else {
                	JSONObject groups = callRequest(getIpaRequest("group_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = groups.getJSONObject("result").getJSONArray("result");
            		for (int i = 0; i < results.length(); ++i) {
            		    JSONObject group = results.getJSONObject(i);
                        ConnectorObject connectorObject = convertGroupToConnectorObject(group);
                        handler.handle(connectorObject);
            		}
					if (results.length() == getConfiguration().getSizelimit()){
						LOG.warn("Sizelimit reached when searching all groups, please increase it over resource config...");
					}
                	// TODO: better paging if possible later...
                }
            }
            else if (objectClass.is(OBJECT_CLASS_HOSTGROUP)) {
                //find by host group name (cn)
                if (lookupKey != null) {
                	JSONObject hostgroup = showOrNull("hostgroup_show", lookupKey);
                	if (hostgroup != null) {
                		handler.handle(convertHostgroupToConnectorObject(hostgroup));
                	}
                } else {
                	JSONObject hostgroups = callRequest(getIpaRequest("hostgroup_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = hostgroups.getJSONObject("result").getJSONArray("result");
            		for (int i = 0; i < results.length(); ++i) {
                        ConnectorObject connectorObject = convertHostgroupToConnectorObject(results.getJSONObject(i));
                        handler.handle(connectorObject);
            		}
					if (results.length() == getConfiguration().getSizelimit()){
						LOG.warn("Sizelimit reached when searching all host groups, please increase it over resource config...");
					}
                }
            }
            else if (objectClass.is(OBJECT_CLASS_HBACRULE)) {
                //find by HBAC rule name (cn)
                if (lookupKey != null) {
                	JSONObject hbacrule = showOrNull("hbacrule_show", lookupKey);
                	if (hbacrule != null) {
                		handler.handle(convertHbacruleToConnectorObject(hbacrule));
                	}
                } else {
                	JSONObject hbacrules = callRequest(getIpaRequest("hbacrule_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = hbacrules.getJSONObject("result").getJSONArray("result");
            		for (int i = 0; i < results.length(); ++i) {
            			JSONObject summary = results.getJSONObject(i);
            			// hbacrule_find summaries omit the membership attributes, so each rule
            			// has to be re-read individually or reconciliation would see every rule
            			// as having no members and unassign everybody.
            			String ruleName = getMultiAsSingleValue(summary, ATTR_CN);
            			JSONObject source = summary;
            			if (ruleName != null && !ruleName.isEmpty()) {
            				try {
            					JSONObject fullRule = callRequest(getIpaRequest("hbacrule_show",
            							new JSONObject().put("all", true), new JSONArray().put(ruleName)));
            					source = fullRule.getJSONObject("result").getJSONObject("result");
            				} catch (Exception e) {
            					LOG.warn("hbacrule_show failed for rule ''{0}'', falling back to summary data (membership may be incomplete): {1}",
            							ruleName, e.getMessage());
            				}
            			}
                        ConnectorObject connectorObject = convertHbacruleToConnectorObject(source);
                        handler.handle(connectorObject);
            		}
					if (results.length() == getConfiguration().getSizelimit()){
						LOG.warn("Sizelimit reached when searching all HBAC rules, please increase it over resource config...");
					}
                }
            }
            else if (objectClass.is(OBJECT_CLASS_HOST)) {
                //find by fully-qualified host name (fqdn)
                if (lookupKey != null) {
                	JSONObject host = showOrNull("host_show", lookupKey);
                	if (host != null) {
                		handler.handle(convertHostToConnectorObject(host));
                	}
                } else {
                	JSONObject hosts = callRequest(getIpaRequest("host_find", new JSONObject().put("all", true), new JSONArray()));
                	JSONArray results = hosts.getJSONObject("result").getJSONArray("result");
            		for (int i = 0; i < results.length(); ++i) {
                        handler.handle(convertHostToConnectorObject(results.getJSONObject(i)));
            		}
					if (results.length() == getConfiguration().getSizelimit()){
						LOG.warn("Sizelimit reached when searching all hosts, please increase it over resource config...");
					}
                }
            }
            else {
                // not found
                throw new UnsupportedOperationException("Unsupported object class " + objectClass);
            }
        } catch (IOException e) {
            throw new ConnectorIOException(e.getMessage(), e);
        }
	}

	private ConnectorObject convertUserToConnectorObject(JSONObject user) throws IOException {
		LOG.ok("JSON User as input: \n{0}", user);
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        ObjectClass objectClass = new ObjectClass(OBJECT_CLASS_USER);
        builder.setObjectClass(objectClass);
        String uid = getMultiAsSingleValue(user, ATTR_UID);
        builder.setUid(new Uid(uid));
        builder.setName(new Name(uid));

        Iterator<String> keys = user.keys();

        while(keys.hasNext()) {
            String key = keys.next();
        	Object value = user.get(key);
//    		LOG.ok("JSON key:{0}={1}", key, value);
			if(getConfiguration().getSupportPreserved()){
				if (key.contains("preserved")) {
					if (Boolean.TRUE.equals(value)){
						preserved_user_list.add(uid);
					}
				}
			}
            if (value instanceof JSONObject || value instanceof Boolean || value instanceof String) {
            	// single value
            	addAttr(builder, key, value);
            } else if (value instanceof JSONArray) {
            	// multi value
            	JSONArray values = user.getJSONArray(key);

            	List<String> valueList = new ArrayList<String>();
            	for(int i = 0; i < values.length(); i++){
            		Object val = values.get(i);

            		if (val instanceof JSONObject) {
                		// handling "__datetime__", "__base64__"...
                		// "krbextradata": [{"__base64__": "AAJeR8pdcm9vdC9hZG1pbkBMQUIuQVJUSU4uSU8A"}]
                		// "krbpasswordexpiration": [{"__datetime__": "20191112054710Z"}]
                		// "krblastpwdchange": [{"__datetime__": "20191112054710Z"}]
            			JSONObject joVal = ((JSONObject)val);
            			Iterator<String> keysForVal = joVal.keys();
            			while(keysForVal.hasNext()) {
            			    String keyForVal = keysForVal.next();
            			    valueList.add(joVal.getString(keyForVal));
            			}
            		}
            		else {
            			valueList.add(String.valueOf(values.get(i)));
            		}
            	}
            	String[] valueArray = valueList.toArray(new String[0]);
                builder.addAttribute(key, valueArray);
            }
        }

        if (user.has(ATTR_NSACCOUNTLOCK) && !DISABLE_ADMINISTRATIVE_STATUS) {
            boolean enabled = !user.getBoolean(ATTR_NSACCOUNTLOCK);
            addAttr(builder, OperationalAttributes.ENABLE_NAME, enabled);
        }

        ConnectorObject connectorObject = builder.build();
        LOG.ok("convertUserToConnectorObject, user: {0}, \n\tconnectorObject: {1}",
        		uid, connectorObject);
        return connectorObject;
	}



	private ConnectorObject convertRoleToConnectorObject(JSONObject role) throws IOException {
		LOG.ok("JSON Role as input: \n{0}", role);
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        ObjectClass objectClass = new ObjectClass(OBJECT_CLASS_ROLE);
        builder.setObjectClass(objectClass);
        String uid = getMultiAsSingleValue(role, ATTR_CN);
        builder.setUid(new Uid(uid));
        builder.setName(new Name(uid));

        Iterator<String> keys = role.keys();

        while(keys.hasNext()) {
            String key = keys.next();

        	Object value = role.get(key);
            if (value instanceof JSONObject || value instanceof Boolean || value instanceof String) {
            	// single value - Boolean and String were previously dropped here, which
            	// silently lost every scalar attribute on roles and groups
            	addAttr(builder, key, value);
            } else if (value instanceof JSONArray) {
            	// multi value
            	JSONArray values = role.getJSONArray(key);

            	List<String> valueList = new ArrayList<String>();
            	for(int i = 0; i < values.length(); i++){
            		valueList.add(String.valueOf(values.get(i)));
            	}
            	String[] valueArray = valueList.toArray(new String[0]);
                builder.addAttribute(key, valueArray);
            }
        }

        ConnectorObject connectorObject = builder.build();
        LOG.ok("convertRoleToConnectorObject, user: {0}, \n\tconnectorObject: {1}",
        		uid, connectorObject);
        return connectorObject;
	}

	private ConnectorObject convertGroupToConnectorObject(JSONObject group) throws IOException {
		LOG.ok("JSON Group as input: \n{0}", group);
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        ObjectClass objectClass = new ObjectClass(OBJECT_CLASS_GROUP);
        builder.setObjectClass(objectClass);
        String uid = getMultiAsSingleValue(group, ATTR_CN);
        builder.setUid(new Uid(uid));
        builder.setName(new Name(uid));

        Iterator<String> keys = group.keys();

        while(keys.hasNext()) {
            String key = keys.next();

        	Object value = group.get(key);
            if (value instanceof JSONObject || value instanceof Boolean || value instanceof String) {
            	// single value - Boolean and String were previously dropped here, which
            	// silently lost every scalar attribute on roles and groups
            	addAttr(builder, key, value);
            } else if (value instanceof JSONArray) {
            	// multi value
            	JSONArray values = group.getJSONArray(key);

            	List<String> valueList = new ArrayList<String>();
            	for(int i = 0; i < values.length(); i++){
            		valueList.add(String.valueOf(values.get(i)));
            	}
            	String[] valueArray = valueList.toArray(new String[0]);
                builder.addAttribute(key, valueArray);
            }
        }

        ConnectorObject connectorObject = builder.build();
        LOG.ok("convertGroupToConnectorObject, group: {0}, \n\tconnectorObject: {1}",
        		uid, connectorObject);
        return connectorObject;
	}

	/** The attribute FreeIPA keys an object class on: fqdn for host, cn for the rest. */
	private static String primaryKeyAttr(String objectClassName) {
		return OBJECT_CLASS_HOST.equals(objectClassName) ? ATTR_FQDN : ATTR_CN;
	}

	/**
	 * Converter for the object classes whose values are plain strings or string arrays
	 * (hostgroup, hbacrule, host). Uses String.valueOf rather than JSONArray.getString so a
	 * numeric or boolean element degrades to its text form instead of throwing and costing
	 * us the whole object.
	 */
	private ConnectorObject convertKeyedToConnectorObject(JSONObject source, String objectClassName) {
		LOG.ok("JSON {0} as input: \n{1}", objectClassName, source);
		ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
		builder.setObjectClass(new ObjectClass(objectClassName));
		String uid = getMultiAsSingleValue(source, primaryKeyAttr(objectClassName));
		builder.setUid(new Uid(uid));
		builder.setName(new Name(uid));

		Iterator<String> keys = source.keys();
		while (keys.hasNext()) {
			String key = keys.next();

			// mepmanagedentry is returned for host groups that own a managed netgroup
			// (objectClass mepOriginEntry) and is a read-only, derived DN pointer of no
			// provisioning value. It is skipped rather than declared, on purpose.
			//
			// Do NOT "fix" this by declaring it in buildStaticObjectClass(): midPoint
			// answers an attribute that is absent from the resource's cached schema with an
			// infinite recursion in LensProjectionContext.getCompositeObjectDefinition()
			// rather than a clean error, so every host group carrying the attribute becomes
			// unreadable (StackOverflowError) until the schema is refreshed. Verified on
			// midPoint 4.10.2 against FreeIPA 4.12.2: hostgroup 'iwg-hosts' broke while
			// 'ipaservers', which has no managed entry, was unaffected.
			if (ATTR_MEPMANAGEDENTRY.equals(key)) {
				continue;
			}

			Object value = source.get(key);
			if (value instanceof JSONArray) {
				JSONArray values = (JSONArray) value;
				List<String> valueList = new ArrayList<String>();
				for (int i = 0; i < values.length(); i++) {
					Object val = values.get(i);
					if (val instanceof JSONObject) {
						// handling "__datetime__", "__base64__", ...
						JSONObject joVal = (JSONObject) val;
						Iterator<String> keysForVal = joVal.keys();
						while (keysForVal.hasNext()) {
							valueList.add(String.valueOf(joVal.get(keysForVal.next())));
						}
					} else {
						valueList.add(String.valueOf(val));
					}
				}
				builder.addAttribute(key, valueList.toArray(new String[0]));
			} else if (value instanceof JSONObject) {
				// a bare "__datetime__" / "__base64__" wrapper - unwrap to its text value
				// rather than dropping the attribute
				JSONObject joVal = (JSONObject) value;
				Iterator<String> keysForVal = joVal.keys();
				while (keysForVal.hasNext()) {
					addAttr(builder, key, String.valueOf(joVal.get(keysForVal.next())));
				}
			} else {
				addAttr(builder, key, value);
			}
		}

		ConnectorObject connectorObject = builder.build();
		LOG.ok("convertKeyedToConnectorObject, {0}: {1}, \n\tconnectorObject: {2}", objectClassName, uid, connectorObject);
		return connectorObject;
	}

	private ConnectorObject convertHostgroupToConnectorObject(JSONObject hostgroup) {
		return convertKeyedToConnectorObject(hostgroup, OBJECT_CLASS_HOSTGROUP);
	}

	private ConnectorObject convertHbacruleToConnectorObject(JSONObject hbacrule) {
		return convertKeyedToConnectorObject(hbacrule, OBJECT_CLASS_HBACRULE);
	}

	private ConnectorObject convertHostToConnectorObject(JSONObject host) {
		return convertKeyedToConnectorObject(host, OBJECT_CLASS_HOST);
	}

	private String getMultiAsSingleValue(JSONObject user, String attrName) {
		if (!user.has(attrName))
			return null;

		Object val = user.get(attrName);
		if (val instanceof JSONObject) {
			return user.getString(attrName);
		}
		else {
        	JSONArray values = user.getJSONArray(attrName);
            if (values.length()==1)
            	return (String) values.get(0);
            else
            	throw new ConnectorException("For attribute: "+attrName+" we have several values: "+values + " for user: "+user);
		}
	}

	@Override
	public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions options) {
		if (objectClass.is(OBJECT_CLASS_USER)) {
            return createUser(attributes);
		} else if (objectClass.is(OBJECT_CLASS_ROLE)) {
            return CreateRole(attributes);
		} else if (objectClass.is(OBJECT_CLASS_GROUP)) {
            return createGroup(attributes);
		} else if (objectClass.is(OBJECT_CLASS_HOSTGROUP)) {
            return createHostgroup(attributes);
		} else if (objectClass.is(OBJECT_CLASS_HBACRULE)) {
            return createHbacrule(attributes);
		} else if (objectClass.is(OBJECT_CLASS_HOST)) {
            return createHost(attributes);
        } else {
            // not found
            throw new UnsupportedOperationException("Unsupported object class " + objectClass);
        }
	}


	private Uid createUser( Set<Attribute> attributes) {
        LOG.ok("createUser, attributes: {1}", attributes);

        JSONObject params = new JSONObject();
        String icfsName = getStringAttr(attributes, Name.NAME); //old or new login to rename

        if (StringUtil.isBlank(icfsName)) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + Name.NAME);
        }
        if (StringUtil.isBlank(getStringAttr(attributes, ATTR_GIVENNAME))) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + ATTR_GIVENNAME);
        }
        if (StringUtil.isBlank(getStringAttr(attributes, ATTR_SN))) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + ATTR_SN);
        }
        if (StringUtil.isBlank(getStringAttr(attributes, ATTR_CN))) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + ATTR_CN);
        }

        final List<String> passwordList = new ArrayList<String>(1);
        GuardedString guardedPassword = getAttr(attributes, OperationalAttributeInfos.PASSWORD.getName(), GuardedString.class);
        if (guardedPassword != null) {
            guardedPassword.access(new GuardedString.Accessor() {
                @Override
                public void access(char[] chars) {
                    passwordList.add(new String(chars));
                }
            });
        }
        String password = null;
        if (!passwordList.isEmpty()) {
            password = passwordList.get(0);
        }
        // workaround for https://www.freeipa.org/page/New_Passwords_Expired
        String krbPasswordExpiration = null;
        for (Attribute attr : attributes) {
        	String attrName = attr.getName();
        	List<Object> attrValue = attr.getValue();
        	if (attrName.equals(FreeIpaConnector.ATTR_KRBPASSWORDEXPIRATION) && attrValue!=null) {
        		krbPasswordExpiration = (String) attrValue.get(0); // need to set password expiration
        	}
        	if (attrName.equals(OperationalAttributeInfos.ENABLE.getName())
        			|| attrName.equals(OperationalAttributeInfos.PASSWORD.getName())
        			|| attrName.equals(ATTR_UID)
        			|| attrName.equals(Name.NAME)
        			|| attrName.equals(FreeIpaConnector.ATTR_MEMBEROF_ROLE)
        			|| attrName.equals(FreeIpaConnector.ATTR_MEMBEROF_GROUP)) {
        		continue; // proceeed in different way...
        	}
    		if (USER_MULTIVALUED.contains(attrName)) {
    			JSONArray values = new JSONArray();
    			if (attrValue!=null) {
            		for (Object av: attrValue)
            			values.put(av);
    			}
        		params.put(attrName, values);
    		}
    		else {
    			if (attrValue==null) {
    				params.put(attrName, JSONObject.NULL);
    			}
    			else {
    				params.put(attrName, attrValue.get(0));
    			}
    		}
        }


        JSONArray params_array = new JSONArray();
        params_array.put(icfsName);
		//if we support preserved accounts, undelete before anything else
		if ( getConfiguration().getSupportPreserved() ){
			if (preserved_user_list.contains(icfsName)){
				JSONObject undelrequest = getIpaRequest("user_undel" , new JSONObject(), params_array);
				try{
					JSONObject undeljores = callRequest(undelrequest);
					LOG.info("response after set user_undel UID: {0}, body: {1}", icfsName, undeljores);
				} catch (Exception all_ex){
					LOG.error("Error running undel, user doesn't exist? Error: {0}", all_ex);
				}
				preserved_user_list.remove(icfsName);
			}
		}

		JSONObject request = getIpaRequest("user_add", params, params_array);

        LOG.ok("user request (without password): {0}", request.toString());

        if (password != null) {
            params.put(ATTR_USERPASSWORD, password);
        }

        if (params.length()>0) {
			JSONObject jores;
			try {
				jores = callRequest(request);
				LOG.info("response UID: {0}, body: {1}", icfsName, jores);
			} catch (AlreadyExistsException e) {
				//shadow deleted, but account still available on resource - not a likely scenario in prod env
				request = getIpaRequest("user_mod" , params, params_array);
				jores = callRequest(request);
				LOG.info("User already existed, ran user_mod - response UID: {0}, body: {1}", icfsName, jores);
			}
		}

        handleEnable(attributes, icfsName, true);
        handleRoles(attributes, icfsName, true);
        handleGroups(attributes, icfsName, true);

        if (krbPasswordExpiration != null) {
        	// set again password expiration
        	JSONObject paramsExp = new JSONObject();
            paramsExp.put(ATTR_KRBPASSWORDEXPIRATION, krbPasswordExpiration);

            JSONArray params_arrayExp = new JSONArray();
            params_arrayExp.put(icfsName);

        	JSONObject requestExp = getIpaRequest("user_mod", paramsExp, params_arrayExp);
	        JSONObject jores = callRequest(requestExp);
	        LOG.info("response after set krbpasswordexpiration UID: {0}, body: {1}", icfsName, jores);
        }

        return new Uid(icfsName);
    }

	@Override
	public Set<AttributeDelta> updateDelta(ObjectClass objectClass, Uid uid, Set<AttributeDelta> modifications, OperationOptions operationOptions) {
		if (objectClass.is(OBJECT_CLASS_USER)) {
			updateDeltaUser(uid, modifications);
			return null;
		} else if (objectClass.is(OBJECT_CLASS_ROLE)) {
			updateDeltaRole(uid, modifications);
			return null;
		} else if (objectClass.is(OBJECT_CLASS_GROUP)) {
			updateDeltaGroup(uid, modifications);
			return null;
		} else if (objectClass.is(OBJECT_CLASS_HOSTGROUP)) {
			return updateDeltaHostgroup(uid, modifications);
		} else if (objectClass.is(OBJECT_CLASS_HBACRULE)) {
			return updateDeltaHbacrule(uid, modifications);
		} else if (objectClass.is(OBJECT_CLASS_HOST)) {
			return updateDeltaHost(uid, modifications);
		} else {
			// not found
			throw new UnsupportedOperationException("Unsupported object class " + objectClass);
		}
	}

	private void updateDeltaRole(Uid uid, Set<AttributeDelta> modifications) {
		LOG.ok("updateDeltaRole, Uid: {0}, deltas: {1}", uid, modifications);
		if (modifications == null || modifications.isEmpty()) {
			LOG.ok("request ignored, empty modifications");
			return;
		}

		Set<Attribute> attributes = new HashSet<>();
		JSONObject currentRole = callRequest(
				getIpaRequest("role_show", new JSONObject().put("all", true), new JSONArray().put(uid.getUidValue()))
		).getJSONObject("result").getJSONObject("result");


		for (AttributeDelta attributeDelta : modifications) {
			String attrName = attributeDelta.getName();

			if (attributeDelta.getValuesToAdd() != null || attributeDelta.getValuesToRemove() != null) {
				LOG.ok("Handling multivalued attribute delta for attribute: {0}", attrName);


				List<Object> currentValues = new ArrayList<>();
				if (currentRole.has(attrName)) {
					JSONArray existingValues = currentRole.getJSONArray(attrName);
					for (int i = 0; i < existingValues.length(); i++) {
						currentValues.add(existingValues.get(i));
					}
				}

				LOG.ok("Current values for attribute {0}: {1}", attrName, currentValues);

				if (attributeDelta.getValuesToAdd() != null) {
					LOG.ok("Values to add for {0}: {1}", attrName, attributeDelta.getValuesToAdd());
					for (Object valToAdd : attributeDelta.getValuesToAdd()) {
						if (!currentValues.contains(valToAdd)) {
							currentValues.add(valToAdd);
						}
					}
				}

				if (attributeDelta.getValuesToRemove() != null) {
					LOG.ok("Values to remove for {0}: {1}", attrName, attributeDelta.getValuesToRemove());
					currentValues.removeAll(attributeDelta.getValuesToRemove());
				}

				Attribute finalAttribute = currentValues.isEmpty()
						? AttributeBuilder.build(attrName, (Object[]) null)
						: AttributeBuilder.build(attrName, currentValues);

				attributes.add(finalAttribute);

			} else if (attributeDelta.getValuesToReplace() != null) {
				for (Object value : attributeDelta.getValuesToReplace()) {
					Attribute attribute = AttributeBuilder.build(attrName, value);
					attributes.add(attribute);
				}
			}
		}

		JSONObject params = new JSONObject();
		String roleNameNew = getStringAttr(attributes, Name.NAME);
		if (StringUtil.isBlank(roleNameNew)) {
			roleNameNew = uid.getUidValue();
		}

		putFieldValueIfExists(attributes, ATTR_DESCRIPTION, params);

		if (!uid.getUidValue().equals(roleNameNew)) {
			params.put(ATTR_RENAME, roleNameNew);
			LOG.ok("Role rename requested from {0} to {1}", uid.getUidValue(), roleNameNew);
		}

		JSONArray params_array = new JSONArray().put(uid.getUidValue());
		JSONObject request = getIpaRequest("role_mod", params, params_array);

		LOG.ok("Role update request: {0}", request.toString());

		if (params.length() > 0) {
			JSONObject jores = callRequest(request);
			LOG.info("Role updated, UID: {0}, response: {1}", roleNameNew, jores);
		}
	}

	private void updateDeltaUser(Uid uid, Set<AttributeDelta> modifications) {
		LOG.ok("updateDeltaUser, Uid: {0}", uid);
		if (modifications == null || modifications.isEmpty()) {
			LOG.ok("request ignored, empty attributes");
			return;
		}
		Set<Attribute> attributes = new HashSet<>();

		JSONObject currentUser = callRequest(
				getIpaRequest("user_show", new JSONObject().put("all", true), new JSONArray().put(uid.getUidValue()))
		).getJSONObject("result").getJSONObject("result");

		for (AttributeDelta attributeDelta: modifications) {
			if (attributeDelta.getValuesToAdd() != null || attributeDelta.getValuesToRemove() != null) {
				LOG.ok("Handling multivalued attribute delta for attribute: {0}", attributeDelta.getName());
				List<Object> currentValues = new ArrayList<>();
				if (currentUser.has(attributeDelta.getName())) {
					JSONArray existingValues = currentUser.getJSONArray(attributeDelta.getName());
					for (int i = 0; i < existingValues.length(); i++) {
						currentValues.add(existingValues.get(i));
					}
				}
				LOG.ok("Current values before modification for attribute {0}: {1}", attributeDelta.getName(), currentValues);

				if (attributeDelta.getValuesToAdd() != null) {
					LOG.ok("Values to add for attribute {0}: {1}", attributeDelta.getName(), attributeDelta.getValuesToAdd());
					for (Object valToAdd : attributeDelta.getValuesToAdd()) {
						if (!currentValues.contains(valToAdd)) {
							currentValues.add(valToAdd);
						}
					}
				}

				if (attributeDelta.getValuesToRemove() != null) {
					LOG.ok("Values to remove for attribute {0}: {1}", attributeDelta.getName(), attributeDelta.getValuesToRemove());
					currentValues.removeAll(attributeDelta.getValuesToRemove());
				}

				LOG.ok("Final values after add/remove for attribute {0}: {1}", attributeDelta.getName(), currentValues);

				Attribute finalAttribute;
				if (currentValues.isEmpty()) {
					finalAttribute = AttributeBuilder.build(attributeDelta.getName(), (Object[]) null);
					LOG.ok("Final attribute {0} is empty after modifications, will clear attribute", attributeDelta.getName());
				} else {
					finalAttribute = AttributeBuilder.build(attributeDelta.getName(), currentValues);
					LOG.ok("Final attribute {0} to be updated with values: {1}", attributeDelta.getName(), currentValues);
				}

				attributes.add(finalAttribute);
			}
			else {
				if (attributeDelta.getValuesToReplace() != null) {
					for (Object value : attributeDelta.getValuesToReplace()) {
						Attribute attribute = AttributeBuilder.build(attributeDelta.getName(), value);
						attributes.add(attribute);
					}
				}
			}
		}
		JSONObject params = new JSONObject();
		String loginNew = getStringAttr(attributes, Name.NAME); //old or new login to rename
		if (StringUtil.isBlank(loginNew)) {
			loginNew = uid.getUidValue();
		}
		final List<String> passwordList = new ArrayList<String>(1);
		GuardedString guardedPassword = getAttr(attributes, OperationalAttributeInfos.PASSWORD.getName(), GuardedString.class);
		if (guardedPassword != null) {
			guardedPassword.access(new GuardedString.Accessor() {
				@Override
				public void access(char[] chars) {
					passwordList.add(new String(chars));
				}
			});
		}
		String password = null;
		if (!passwordList.isEmpty()) {
			password = passwordList.get(0);
		}
		// workaround for https://www.freeipa.org/page/New_Passwords_Expired
		String krbPasswordExpiration = null;
		for (Attribute attr : attributes) {
			String attrName = attr.getName();
			List<Object> attrValue = attr.getValue();
			if (attrName.equals(FreeIpaConnector.ATTR_KRBPASSWORDEXPIRATION) && attrValue!=null) {
				krbPasswordExpiration = (String) attrValue.get(0); // need to set password expiration
			}
			if (attrName.equals(OperationalAttributeInfos.ENABLE.getName())
					|| attrName.equals(OperationalAttributeInfos.PASSWORD.getName())
					|| attrName.equals(ATTR_UID)
					|| attrName.equals(Name.NAME)
					|| attrName.equals(FreeIpaConnector.ATTR_MEMBEROF_ROLE)
					|| attrName.equals(FreeIpaConnector.ATTR_MEMBEROF_GROUP)) {
				continue; // proceeed in different way...
			}
			if (USER_MULTIVALUED.contains(attrName)) {
				JSONArray values = new JSONArray();
				if (attrValue!=null) {
					for (Object av: attrValue)
						values.put(av);
				}
				params.put(attrName, values);
			}
			else {
				if (attrValue==null) {
					params.put(attrName, JSONObject.NULL);
				}
				else {
					params.put(attrName, attrValue.get(0));
				}
			}
		}

		if (!uid.getUidValue().equals(loginNew)) {
			params.put(ATTR_RENAME, loginNew); // rename user, https://www.redhat.com/archives/freeipa-users/2014-March/msg00072.html
		}

		JSONArray params_array = new JSONArray();
		params_array.put(uid.getUidValue());
		//if we support preserved accounts, undelete before anything else
		if ( getConfiguration().getSupportPreserved() ){
			if (preserved_user_list.contains(loginNew)){
				JSONObject undelrequest = getIpaRequest("user_undel" , new JSONObject(), params_array);
				try{
					JSONObject undeljores = callRequest(undelrequest);
					LOG.info("response after set user_undel UID: {0}, body: {1}", loginNew, undeljores);
				} catch (Exception all_ex){
					LOG.error("Error running undel, user doesn't exist? Error: {0}", all_ex);
				}
				preserved_user_list.remove(loginNew);
			}
		}

		JSONObject request = getIpaRequest("user_mod", params, params_array);

		LOG.ok("user request (without password): {0}", request.toString());

		if (password != null) {
			params.put(ATTR_USERPASSWORD, password);
		}

		if (params.length()>0) {
			JSONObject jores;
			try {
				jores = callRequest(request);
				LOG.info("response UID: {0}, body: {1}", loginNew, jores);
			} catch (AlreadyExistsException e) {
				//shadow deleted, but account still available on resource - not a likely scenario in prod env
				request = getIpaRequest("user_mod" , params, params_array);
				jores = callRequest(request);
				LOG.info("User already existed, ran user_mod - response UID: {0}, body: {1}", loginNew, jores);
			}
		}

		handleEnable(attributes, loginNew, false);
		handleRoles(attributes, loginNew, false);
		handleGroups(attributes, loginNew, false);

		if (krbPasswordExpiration != null) {
			// set again password expiration
			JSONObject paramsExp = new JSONObject();
			paramsExp.put(ATTR_KRBPASSWORDEXPIRATION, krbPasswordExpiration);

			JSONArray params_arrayExp = new JSONArray();
			params_arrayExp.put(uid.getUidValue());

			JSONObject requestExp = getIpaRequest("user_mod", paramsExp, params_arrayExp);
			JSONObject jores = callRequest(requestExp);
			LOG.info("response after set krbpasswordexpiration UID: {0}, body: {1}", loginNew, jores);
		}

	}

	private Uid CreateRole(Set<Attribute> attributes) {
        LOG.ok("CreateRole, attributes: {1}", attributes);
        JSONObject params = new JSONObject();
        String icfsName = getStringAttr(attributes, Name.NAME); //old or new login to rename
        if (StringUtil.isBlank(icfsName)) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + Name.NAME);
        }

        putFieldValueIfExists(attributes, ATTR_DESCRIPTION, params);

        JSONArray params_array = new JSONArray();
        params_array.put(icfsName);
		JSONObject request = getIpaRequest("role_add", params, params_array);

        LOG.ok("Role request {0}", request.toString());

        if (params.length()>0) {
	        JSONObject jores = callRequest(request);
	        LOG.info("response UID: {0}, body: {1}", icfsName, jores);
        }

        return new Uid(icfsName);
    }

	private Uid createGroup(Set<Attribute> attributes) {
        LOG.ok("createGroup, Uid: {0}, attributes: {1}",attributes);
        JSONObject params = new JSONObject();
        String icfsName = getStringAttr(attributes, Name.NAME); //old or new login to rename


        if (StringUtil.isBlank(icfsName)) {
            throw new InvalidAttributeValueException("Missing mandatory attribute " + Name.NAME);
        }

        for (Attribute attr : attributes) {
        	String attrName = attr.getName();
        	if (attrName.equals(ATTR_UID)
        			|| attrName.equals(Name.NAME)) {
        		continue; // proceeed in different way...
        	}

        	List<Object> attrValue = attr.getValue();

    		if (USER_MULTIVALUED.contains(attrName)) {
        		JSONArray values = new JSONArray();
        		for (Object av: attrValue)
        			values.put(av);
        		params.put(attrName, values);
    		}
    		else {
				if (attrValue==null) {
					params.put(attrName, JSONObject.NULL);
				}
				else {
					params.put(attrName, attrValue.get(0));
				}
    		}
        }

        JSONArray params_array = new JSONArray();
        params_array.put(icfsName);
		JSONObject request = getIpaRequest("group_add", params, params_array);

        LOG.ok("Group request {0}", request.toString());

        if (params.length()>0) {
	        JSONObject jores = callRequest(request);
	        LOG.info("response UID: {0}, body: {1}", icfsName, jores);
        }

        return new Uid(icfsName);
    }

	private void updateDeltaGroup(Uid uid, Set<AttributeDelta> modifications) {
		LOG.ok("updateDeltaGroup, Uid: {0}, deltas: {1}", uid, modifications);

		if (modifications == null || modifications.isEmpty()) {
			LOG.ok("request ignored, empty modifications");
			return;
		}

		Set<Attribute> attributes = new HashSet<>();
		JSONObject currentGroup = callRequest(
				getIpaRequest("group_show", new JSONObject().put("all", true), new JSONArray().put(uid.getUidValue()))
		).getJSONObject("result").getJSONObject("result");

		for (AttributeDelta delta : modifications) {
			String attrName = delta.getName();

			// Handle multi-valued add/remove
			if ((delta.getValuesToAdd() != null || delta.getValuesToRemove() != null)) {

				LOG.ok("Handling multivalued attribute delta for attribute: {0}", attrName);

				List<Object> currentValues = new ArrayList<>();
				if (currentGroup.has(attrName)) {
					JSONArray existing = currentGroup.getJSONArray(attrName);
					for (int i = 0; i < existing.length(); i++) {
						currentValues.add(existing.get(i));
					}
				}

				if (delta.getValuesToAdd() != null) {
					for (Object val : delta.getValuesToAdd()) {
						if (!currentValues.contains(val)) {
							currentValues.add(val);
						}
					}
				}

				if (delta.getValuesToRemove() != null) {
					currentValues.removeAll(delta.getValuesToRemove());
				}

				Attribute finalAttr = currentValues.isEmpty()
						? AttributeBuilder.build(attrName, (Object[]) null)
						: AttributeBuilder.build(attrName, currentValues);

				LOG.ok("Final merged values for group attribute {0}: {1}", attrName, currentValues);
				attributes.add(finalAttr);
			}

			// Handle REPLACE values
			else if (delta.getValuesToReplace() != null) {
				List<Object> replace = delta.getValuesToReplace();
				Attribute attr = (replace == null || replace.isEmpty())
						? AttributeBuilder.build(attrName, (Object[]) null)
						: AttributeBuilder.build(attrName, replace);
				attributes.add(attr);
			}
		}

		JSONObject params = new JSONObject();
		String groupNameNew = getStringAttr(attributes, Name.NAME);
		if (StringUtil.isBlank(groupNameNew)) {
			groupNameNew = uid.getUidValue();
		}

		for (Attribute attr : attributes) {
			String attrName = attr.getName();
			if (attrName.equals(Name.NAME) || attrName.equals(ATTR_UID)) {
				continue;
			}

			List<Object> val = attr.getValue();
			if (USER_MULTIVALUED.contains(attrName)) {
				JSONArray arr = new JSONArray();
				if (val != null) {
					for (Object o : val) {
						arr.put(o);
					}
				}
				params.put(attrName, arr);
			} else {
				if (val == null || val.isEmpty()) {
					params.put(attrName, JSONObject.NULL);
				} else {
					params.put(attrName, val.get(0));
				}
			}
		}

		// Handle rename if NAME changed
		if (!uid.getUidValue().equals(groupNameNew)) {
			params.put(ATTR_RENAME, groupNameNew);
			LOG.ok("Renaming group from {0} to {1}", uid.getUidValue(), groupNameNew);
		}

		JSONArray params_array = new JSONArray().put(uid.getUidValue());
		JSONObject request = getIpaRequest("group_mod", params, params_array);

		LOG.ok("Group update request: {0}", request.toString());

		if (!params.isEmpty()) {
			JSONObject response = callRequest(request);
			LOG.info("Group updated: {0}, response: {1}", groupNameNew, response);
		}
	}



	private void putFieldValueIfExists(Set<Attribute> attributes, String fieldName, JSONObject jo) {
        String value = getStringAttr(attributes, fieldName);
        if (value != null) {
            jo.put(fieldName, value);
        }
    }

	// ---------------------------------------------------------------------------------
	// hostgroup / hbacrule
	//
	// Membership on these classes is not settable through *_mod: FreeIPA exposes it only
	// through the *_add_member / *_remove_member style commands. Every membership attribute
	// is therefore routed through applyMembershipDelta(), which handles add, remove AND
	// replace identically. Handling them one attribute at a time is what previously left
	// memberhost_host without a replace branch, so a replace delta was silently discarded.
	// ---------------------------------------------------------------------------------

	/** Attributes FreeIPA computes or derives; never send them back on create or update. */
	private static final List<String> WRITE_IGNORED_ATTRS = Arrays.asList(
			ATTR_DN, ATTR_IPAUNIQUEID, ATTR_OBJECTCLASS, ATTR_MEPMANAGEDENTRY, ATTR_CN,
			// host: the fqdn is the primary key and is passed positionally, never as a
			// parameter. The rest are derived from the Kerberos principal and the enrolled
			// key material, and host_mod rejects them.
			ATTR_FQDN, "krbprincipalname", "krbcanonicalname", "has_keytab", "has_password",
			"sshpubkeyfp", "serverhostname", "ipacertmapdata");

	/**
	 * Membership attribute -> the FreeIPA commands and parameter name that maintain it.
	 * Order of fields: show command, add command, remove command, request parameter,
	 * attribute to read the current value back from.
	 *
	 * The last field matters for the "memberhost" alias: FreeIPA accepts it as input on some
	 * versions but always answers hostgroup_show with "member_host", so a replace has to
	 * diff against that name or it would read an absent attribute, conclude the group is
	 * empty and never remove anything.
	 */
	private static String[] membershipBinding(String objectClassName, String attributeName) {
		if (OBJECT_CLASS_HOSTGROUP.equals(objectClassName)) {
			if (ATTR_MEMBER_HOST.equals(attributeName) || "memberhost".equals(attributeName)) {
				return new String[] { "hostgroup_show", "hostgroup_add_member", "hostgroup_remove_member", "host", ATTR_MEMBER_HOST };
			}
			if ("member_hostgroup".equals(attributeName)) {
				return new String[] { "hostgroup_show", "hostgroup_add_member", "hostgroup_remove_member", "hostgroup", "member_hostgroup" };
			}
			return null;
		}
		if (OBJECT_CLASS_HBACRULE.equals(objectClassName)) {
			if (ATTR_MEMBERUSER_USER.equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_user", "hbacrule_remove_user", "user", ATTR_MEMBERUSER_USER };
			}
			if ("memberuser_group".equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_user", "hbacrule_remove_user", "group", "memberuser_group" };
			}
			if (ATTR_MEMBERHOST_HOST.equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_host", "hbacrule_remove_host", "host", ATTR_MEMBERHOST_HOST };
			}
			if ("memberhost_hostgroup".equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_host", "hbacrule_remove_host", "hostgroup", "memberhost_hostgroup" };
			}
			if ("memberservice_hbacsvc".equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_service", "hbacrule_remove_service", "hbacsvc", "memberservice_hbacsvc" };
			}
			if ("memberservice_hbacsvcgroup".equals(attributeName)) {
				return new String[] { "hbacrule_show", "hbacrule_add_service", "hbacrule_remove_service", "hbacsvcgroup", "memberservice_hbacsvcgroup" };
			}
			return null;
		}
		return null;
	}

	private void callMembershipCommand(String command, String param, String value, String target) {
		LOG.ok("run command {0} with {1}=''{2}'' on ''{3}''", command, param, value, target);
		JSONObject params_value = new JSONObject().put(param, value);
		JSONArray params_array = new JSONArray().put(target);
		JSONObject jores = callRequest(getIpaRequest(command, params_value, params_array));
		LOG.info("response body for {0} on {1}: {2}", command, target, jores);
		checkMemberOperationResult(jores, command, target);
	}

	private Set<String> readCurrentMembers(String showCommand, String attributeName, String target) {
		Set<String> current = new HashSet<>();
		JSONObject shown;
		try {
			shown = callRequest(getIpaRequest(showCommand, new JSONObject().put("all", true),
					new JSONArray().put(target))).getJSONObject("result").getJSONObject("result");
		} catch (Exception e) {
			// Returning an empty set here would make a replace look like "remove nothing",
			// leaving stale members behind while reporting success. Fail instead.
			throw new ConnectorException("Cannot compute membership replace for '" + target
					+ "': failed to read current " + attributeName + " via " + showCommand, e);
		}
		Object value = shown.opt(attributeName);
		if (value instanceof JSONArray) {
			JSONArray arr = (JSONArray) value;
			for (int i = 0; i < arr.length(); i++) {
				current.add(String.valueOf(arr.get(i)));
			}
		} else if (value != null && !JSONObject.NULL.equals(value)) {
			current.add(String.valueOf(value));
		}
		return current;
	}

	private static Set<String> toStringSet(List<Object> values) {
		Set<String> result = new HashSet<>();
		if (values != null) {
			for (Object value : values) {
				if (value != null && !String.valueOf(value).isEmpty()) {
					result.add(String.valueOf(value));
				}
			}
		}
		return result;
	}

	/** Applies add, remove or replace on one membership attribute. */
	private void applyMembershipDelta(String[] binding, AttributeDelta delta, String target) {
		String showCommand = binding[0];
		String addCommand = binding[1];
		String removeCommand = binding[2];
		String param = binding[3];
		String readAttribute = binding[4];
		String attributeName = delta.getName();

		if (delta.getValuesToReplace() != null) {
			// Expressed as a diff against what FreeIPA currently holds.
			Set<String> desired = toStringSet(delta.getValuesToReplace());
			Set<String> current = readCurrentMembers(showCommand, readAttribute, target);
			LOG.ok("Replacing {0} on ''{1}'': current={2}, desired={3}", attributeName, target, current, desired);
			for (String value : desired) {
				if (!current.contains(value)) {
					callMembershipCommand(addCommand, param, value, target);
				}
			}
			for (String value : current) {
				if (!desired.contains(value)) {
					callMembershipCommand(removeCommand, param, value, target);
				}
			}
			return;
		}

		for (String value : toStringSet(delta.getValuesToAdd())) {
			callMembershipCommand(addCommand, param, value, target);
		}
		for (String value : toStringSet(delta.getValuesToRemove())) {
			callMembershipCommand(removeCommand, param, value, target);
		}
	}

	/**
	 * FreeIPA does not accept ipaenabledflag through hbacrule_mod - it is maintained with
	 * hbacrule_enable / hbacrule_disable. Returns true when the value was handled.
	 */
	private boolean handleHbacruleEnabledFlag(String value, String ruleName) {
		if (value == null) {
			return false;
		}
		String command = Boolean.parseBoolean(value) ? "hbacrule_enable" : "hbacrule_disable";
		JSONObject jores = callRequest(getIpaRequest(command, new JSONObject(), new JSONArray().put(ruleName)));
		LOG.info("response body for {0} on {1}: {2}", command, ruleName, jores);
		return true;
	}

	private Uid createHostgroup(Set<Attribute> attributes) {
		return createKeyed(attributes, OBJECT_CLASS_HOSTGROUP, "hostgroup_add");
	}

	private Uid createHbacrule(Set<Attribute> attributes) {
		return createKeyed(attributes, OBJECT_CLASS_HBACRULE, "hbacrule_add");
	}

	private Uid createHost(Set<Attribute> attributes) {
		return createKeyed(attributes, OBJECT_CLASS_HOST, "host_add");
	}

	private Uid createKeyed(Set<Attribute> attributes, String objectClassName, String addCommand) {
		LOG.ok("create {0}, attributes: {1}", objectClassName, attributes);

		String icfsName = getStringAttr(attributes, Name.NAME);
		if (StringUtil.isBlank(icfsName)) {
			throw new InvalidAttributeValueException("Missing mandatory attribute " + Name.NAME);
		}

		JSONObject params = new JSONObject();
		String enabledFlag = null;
		Map<String, Attribute> memberships = new LinkedHashMap<>();

		for (Attribute attr : attributes) {
			String attrName = attr.getName();
			if (attrName.equals(Name.NAME) || attrName.equals(ATTR_UID)
					|| attrName.equals(OperationalAttributeInfos.PASSWORD.getName())
					|| WRITE_IGNORED_ATTRS.contains(attrName)) {
				continue;
			}
			if (membershipBinding(objectClassName, attrName) != null) {
				memberships.put(attrName, attr); // applied after the object exists
				continue;
			}
			if (isMembershipAttribute(attrName)) {
				continue; // read-only membership projections (memberof_*, memberindirect_*, ...)
			}
			if (ATTR_IPAENABLEDFLAG.equals(attrName)) {
				enabledFlag = getStringAttr(attributes, ATTR_IPAENABLEDFLAG);
				continue;
			}
			List<Object> attrValue = attr.getValue();
			if (attrValue == null || attrValue.isEmpty()) {
				continue; // nothing to set on create
			}
			params.put(attrName, attrValue.get(0));
		}

		if (OBJECT_CLASS_HOST.equals(objectClassName)) {
			// Without this, host_add refuses any name that has no DNS A record - which is the
			// normal case when host entries are provisioned ahead of, or independently of,
			// DNS. FreeIPA still requires the name to be fully qualified.
			params.put(ATTR_FORCE, true);
		}

		JSONArray params_array = new JSONArray();
		params_array.put(icfsName);
		JSONObject jores = callRequest(getIpaRequest(addCommand, params, params_array));
		LOG.info("{0} created: {1}, body: {2}", objectClassName, icfsName, jores);

		// Membership has to follow the add: FreeIPA rejects it as an *_add parameter. That
		// makes the operation non-atomic - and now that a refused member raises instead of
		// being swallowed, a failure here would otherwise leave the bare object behind in
		// FreeIPA while midPoint discards the shadow. The orphan is invisible until the next
		// reconciliation adopts it, after which the provisioning task sees a live shadow,
		// skips creation, and the object stays permanently membership-less while looking
		// provisioned. So: undo the add and let the original error surface.
		try {
			for (Attribute attr : memberships.values()) {
				String[] binding = membershipBinding(objectClassName, attr.getName());
				List<Object> vals = attr.getValue();
				if (vals == null) {
					continue;
				}
				for (String value : toStringSet(vals)) {
					callMembershipCommand(binding[1], binding[3], value, icfsName);
				}
			}

			if (OBJECT_CLASS_HBACRULE.equals(objectClassName) && enabledFlag != null) {
				handleHbacruleEnabledFlag(enabledFlag, icfsName);
			}
		} catch (RuntimeException e) {
			rollbackCreate(objectClassName, icfsName, e);
			throw e;
		}

		return new Uid(icfsName);
	}

	/**
	 * Deletes an object this call had just created, after a follow-up step failed. Only ever
	 * invoked when the preceding *_add returned success, so it can never remove something
	 * that existed beforehand. A failure to roll back is logged, not thrown - the caller is
	 * about to rethrow the real cause and that must not be masked.
	 */
	private void rollbackCreate(String objectClassName, String name, RuntimeException cause) {
		String delCommand = objectClassName + "_del";
		LOG.warn("Rolling back {0} ''{1}'': {2}", objectClassName, name, cause.getMessage());
		try {
			callRequest(getIpaRequest(delCommand, new JSONObject(), new JSONArray().put(name)));
			LOG.ok("Rolled back {0} ''{1}''", objectClassName, name);
		} catch (RuntimeException rollbackFailure) {
			LOG.error("Could not roll back {0} ''{1}'' - it is left in FreeIPA without its "
					+ "membership and will need removing by hand: {2}",
					objectClassName, name, rollbackFailure.getMessage());
		}
	}

	private Set<AttributeDelta> updateDeltaHostgroup(Uid uid, Set<AttributeDelta> modifications) {
		return updateDeltaKeyed(uid, modifications, OBJECT_CLASS_HOSTGROUP, "hostgroup_mod");
	}

	private Set<AttributeDelta> updateDeltaHbacrule(Uid uid, Set<AttributeDelta> modifications) {
		return updateDeltaKeyed(uid, modifications, OBJECT_CLASS_HBACRULE, "hbacrule_mod");
	}

	private Set<AttributeDelta> updateDeltaHost(Uid uid, Set<AttributeDelta> modifications) {
		return updateDeltaKeyed(uid, modifications, OBJECT_CLASS_HOST, "host_mod");
	}

	/**
	 * Returns the side-effect changes for the operation - specifically the new Uid after a
	 * rename. cn is the primary identifier for these classes, so a rename changes the Uid;
	 * returning null would leave midPoint holding the old one, marking the shadow dead and
	 * letting the next reconciliation create a duplicate.
	 */
	private Set<AttributeDelta> updateDeltaKeyed(Uid uid, Set<AttributeDelta> modifications, String objectClassName, String modCommand) {
		LOG.ok("updateDelta{0}, Uid: {1}, deltas: {2}", objectClassName, uid, modifications);

		if (modifications == null || modifications.isEmpty()) {
			LOG.ok("request ignored, empty modifications");
			return null;
		}

		String target = uid.getUidValue();
		JSONObject params = new JSONObject();
		String newName = null;
		String enabledFlag = null;

		for (AttributeDelta delta : modifications) {
			String attrName = delta.getName();

			if (Uid.NAME.equals(attrName) || WRITE_IGNORED_ATTRS.contains(attrName)) {
				continue;
			}

			if (Name.NAME.equals(attrName)) {
				List<Object> toReplace = delta.getValuesToReplace();
				if (toReplace != null && !toReplace.isEmpty()) {
					newName = String.valueOf(toReplace.get(0));
				}
				continue;
			}

			String[] binding = membershipBinding(objectClassName, attrName);
			if (binding != null) {
				applyMembershipDelta(binding, delta, target);
				continue;
			}

			if (isMembershipAttribute(attrName)) {
				LOG.ok("Ignoring read-only membership projection {0} on {1}", attrName, target);
				continue;
			}

			if (ATTR_IPAENABLEDFLAG.equals(attrName)) {
				List<Object> toReplace = delta.getValuesToReplace();
				if (toReplace != null && !toReplace.isEmpty()) {
					enabledFlag = String.valueOf(toReplace.get(0));
				}
				continue;
			}

			// Plain single-valued attribute: description, accessruletype, the *category
			// attributes. An empty replace clears the value.
			List<Object> toReplace = delta.getValuesToReplace();
			if (toReplace != null) {
				params.put(attrName, toReplace.isEmpty() ? JSONObject.NULL : toReplace.get(0));
			} else {
				LOG.warn("Attribute {0} on {1} is single-valued in FreeIPA but received an add/remove delta; ignoring",
						attrName, objectClassName);
			}
		}

		if (newName != null && !target.equals(newName)) {
			if (OBJECT_CLASS_HOST.equals(objectClassName)) {
				// host_mod has no rename option: in FreeIPA a host's fqdn is fixed for the
				// life of the entry, because the Kerberos principal and any issued
				// certificates are derived from it. Sending rename would draw error 3005,
				// unknown option; failing here says why instead.
				throw new ConnectorException("FreeIPA cannot rename host '" + target
						+ "': a host's fqdn is immutable. Delete the host and create '"
						+ newName + "' instead.");
			}
			params.put(ATTR_RENAME, newName);
			LOG.ok("Renaming {0} from {1} to {2}", objectClassName, target, newName);
		}

		if (!params.isEmpty()) {
			JSONObject jores = callRequest(getIpaRequest(modCommand, params, new JSONArray().put(target)));
			LOG.info("{0} updated: {1}, response: {2}", objectClassName, target, jores);
		}

		if (enabledFlag != null && OBJECT_CLASS_HBACRULE.equals(objectClassName)) {
			handleHbacruleEnabledFlag(enabledFlag, newName != null ? newName : target);
		}

		if (newName != null && !target.equals(newName)) {
			// Hand the new identifier back, or midPoint keeps the pre-rename Uid.
			Set<AttributeDelta> sideEffects = new HashSet<>();
			sideEffects.add(AttributeDeltaBuilder.build(Uid.NAME, newName));
			return sideEffects;
		}

		return null;
	}

	private void handleRoles(Set<Attribute> attributes, String login, boolean create) {
    	for (Attribute attr : attributes) {
    		if (ATTR_MEMBEROF_ROLE.equals(attr.getName())) {
    			List<Object> vals = attr.getValue();
				if (create) {
					// is enought to add new role assignments, not need to read user details
					for (Object val : vals) {
    		            addRemoveMember("role_add_member", login, (String) val);
					}
				}
				else {
                	JSONObject user = callRequest(getIpaRequest("user_show", new JSONArray().put(login))).getJSONObject("result").getJSONObject("result");
                	JSONArray currentRoles = new JSONArray();
                	if (user.has(ATTR_MEMBEROF_ROLE))
                		currentRoles = user.getJSONArray(ATTR_MEMBEROF_ROLE);
	    			if (vals==null || vals.isEmpty()) {
	    				//need to remove all current roles
	    				for (int i = 0; i < currentRoles.length(); i++) {
							addRemoveMember("role_remove_member", login, currentRoles.getString(i));
	    				}
	    			}
	    			if (vals != null) {
	        			// need to add new ones
	    				for (Object val : vals) {
							String role = (String) val;
							boolean needToAdd = true;
		    				for (int i = 0; i < currentRoles.length(); i++) {
								if (role.equals(currentRoles.getString(i))) {
									needToAdd = false;
								}
		    				}
							if (needToAdd) {
								addRemoveMember("role_add_member", login, role);
							}
						}
	    				// need to remove old ones
	    				for (int i = 0; i < currentRoles.length(); i++) {
	    					String currentRole = currentRoles.getString(i);
	    					boolean needToRemove = true;
	    					for (Object val : vals) {
								String role = (String) val;
								if (currentRole.equals(role)) {
									needToRemove = false;
								}
	    					}
							if (needToRemove) {
								addRemoveMember("role_remove_member", login, currentRole);
							}
	    				}
	    			}
				}
    		}
    	}
	}

    private void handleGroups(Set<Attribute> attributes, String login, boolean create) {
    	for (Attribute attr : attributes) {
    		if (ATTR_MEMBEROF_GROUP.equals(attr.getName())) {
    			List<Object> vals = attr.getValue();
				if (create) {
					// is enought to add new role assignments, not need to read user details
					for (Object val : vals) {
    		            addRemoveMember("group_add_member", login, (String) val);
					}
				}
				else {
                	JSONObject user = callRequest(getIpaRequest("user_show", new JSONArray().put(login))).getJSONObject("result").getJSONObject("result");
                	JSONArray currentGroups = new JSONArray();
                	if (user.has(ATTR_MEMBEROF_GROUP))
                		currentGroups = user.getJSONArray(ATTR_MEMBEROF_GROUP);
	    			if (vals==null || vals.isEmpty()) {
	    				//need to remove all current roles
	    				for (int i = 0; i < currentGroups.length(); i++) {
							addRemoveMember("group_remove_member", login, currentGroups.getString(i));
	    				}
	    			}
	    			if (vals != null) {
	        			// need to add new ones
	    				for (Object val : vals) {
							String group = (String) val;
							boolean needToAdd = true;
		    				for (int i = 0; i < currentGroups.length(); i++) {
								if (group.equals(currentGroups.getString(i))) {
									needToAdd = false;
								}
		    				}
							if (needToAdd) {
								addRemoveMember("group_add_member", login, group);
							}
						}
	    				// need to remove old ones
	    				for (int i = 0; i < currentGroups.length(); i++) {
	    					String currentGroup = currentGroups.getString(i);
	    					boolean needToRemove = true;
	    					for (Object val : vals) {
								String group = (String) val;
								if (currentGroup.equals(group)) {
									needToRemove = false;
								}
	    					}
							if (needToRemove) {
								addRemoveMember("group_remove_member", login, currentGroup);
							}
	    				}
	    			}
				}
    		}
    	}
	}
	private void addRemoveMember(String command, String login, String roleOrGroupName) {
        LOG.ok("run command {0} on user {1} and {2}", command, login, roleOrGroupName);

        JSONObject params_value = new JSONObject().put("user", login);

		JSONArray params_array = new JSONArray();
        params_array.put(roleOrGroupName);

		JSONObject request = getIpaRequest(command, params_value, params_array);
		JSONObject jores;
		try {
			jores = callRequest(request);
		} catch (UnknownUidException e) {
			// The group or role is the primary key of *_add_member / *_remove_member, so a
			// FreeIPA "NotFound" here means the GROUP is missing - not the account. But
			// processFreeIpaResponseErrors maps every NotFound to UnknownUidException, and
			// midPoint reads that, mid-modify, as "the object I was modifying no longer
			// exists": it marks the user's shadow dead and tombstones it, and the next
			// recompute then creates a duplicate. Verified on midPoint 4.10.2 - assigning a
			// user to a group absent from FreeIPA killed the account's shadow.
			//
			// The original exception is deliberately NOT chained as the cause:
			// ConnIdUtil.lookForKnownCause() walks the whole cause chain and would find the
			// UnknownUidException regardless of the wrapper, translating it to
			// ObjectNotFoundException and tombstoning the shadow anyway. Logged instead.
			LOG.error("{0} failed for user {1}: {2}", command, login, e.getMessage());
			throw new ConnectorException("Cannot run " + command + " for user '" + login
					+ "': FreeIPA has no group or role named '" + roleOrGroupName + "'");
		}

        LOG.info("response body for {0} on {1}: {2}", command, login, jores);
        checkMemberOperationResult(jores, command, roleOrGroupName);
	}

	private void handleEnable(Set<Attribute> attributes, String login, boolean create) {
        Boolean enable = getAttr(attributes, OperationalAttributes.ENABLE_NAME, Boolean.class);

        if (enable != null && !(create && enable)) {
        	String command = enable ? "user_enable": "user_disable";
            JSONArray params_array = new JSONArray();
            params_array.put(login);

    		JSONObject request = getIpaRequest(command, new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for command: {1}", jores, command);
        }
    }

	@Override
	public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
		if (objectClass.is(OBJECT_CLASS_USER)) {
			// TODO also preserve=true & user_undel?
            LOG.ok("delete user, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
			// updated to support preserved accounts
			JSONObject pparams = new JSONObject();
			if (getConfiguration().getSupportPreserved()) {
				LOG.ok("preserve user, Uid: {0}", uid);
				pparams.put("preserve", true);
			}
			JSONObject request = getIpaRequest("user_del", pparams, params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for user deletion for uid: {1}", jores, uid);
		} else if (objectClass.is(OBJECT_CLASS_ROLE)) {
            LOG.ok("delete role, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
    		JSONObject request = getIpaRequest("role_del", new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for role deletion for uid: {1}", jores, uid);
		} else if (objectClass.is(OBJECT_CLASS_GROUP)) {
            LOG.ok("delete group, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
    		JSONObject request = getIpaRequest("group_del", new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for group deletion for uid: {1}", jores, uid);
		} else if (objectClass.is(OBJECT_CLASS_HOSTGROUP)) {
            LOG.ok("delete hostgroup, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
    		JSONObject request = getIpaRequest("hostgroup_del", new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for hostgroup deletion for uid: {1}", jores, uid);
		} else if (objectClass.is(OBJECT_CLASS_HBACRULE)) {
            LOG.ok("delete hbacrule, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
    		JSONObject request = getIpaRequest("hbacrule_del", new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for hbacrule deletion for uid: {1}", jores, uid);
		} else if (objectClass.is(OBJECT_CLASS_HOST)) {
            LOG.ok("delete host, Uid: {0}", uid);
            JSONArray params_array = new JSONArray();
            params_array.put(uid.getUidValue());
    		JSONObject request = getIpaRequest("host_del", new JSONObject(), params_array);
    		JSONObject jores = callRequest(request);
            LOG.info("response body: {0} for host deletion for uid: {1}", jores, uid);
        } else {
            // not found
            throw new UnsupportedOperationException("Unsupported object class " + objectClass);
        }
	}

}
