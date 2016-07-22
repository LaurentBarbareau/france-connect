package net.fenyo.franceconnect;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;

import org.mitre.openid.connect.model.OIDCAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import com.google.common.escape.ArrayBasedUnicodeEscaper;

import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

@Controller
public class WebController {
	private static final Logger logger = LoggerFactory.getLogger(WebController.class);

	@Autowired
    private OidcAttributes oidcAttributes;
	
	@RequestMapping(value = "/", method = RequestMethod.GET)
	public ModelAndView home() {
		final ModelAndView mav = new ModelAndView("home");
		mav.addObject("oidcAttributes", oidcAttributes);
		return mav;
	}

	@RequestMapping(value = "/user", method = RequestMethod.GET)
	@PreAuthorize("isFullyAuthenticated()")
	public ModelAndView user(final Principal p) {
    	final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		OIDCAuthenticationToken oidcauth = (OIDCAuthenticationToken) auth;

		// exemples d'accès en Java aux informations d'authentification :
		//   oidcauth.getIdToken().getJWTClaimsSet().getIssuer()
		//   oidcauth.getUserInfo().getAddress().getPostalCode()
		
		final ModelAndView mav = new ModelAndView("user");
		mav.addObject("oidcBirthplace", oidcauth.getUserInfo().getSource().get("birthplace"));
		mav.addObject("oidcBirthcountry", oidcauth.getUserInfo().getSource().get("birthcountry"));
		mav.addObject("oidcAttributes", oidcAttributes);
		return mav;
	}

	@RequestMapping(value = "/authenticationError", method = RequestMethod.GET)
	public ModelAndView authenticationError() {
		final ModelAndView mav = new ModelAndView("authenticationError");
		mav.addObject("oidcAttributes", oidcAttributes);
		return mav;
	}

	@RequestMapping(value = "/idp", method = RequestMethod.GET)
	@PreAuthorize("isFullyAuthenticated()")
	public RedirectView idp(final HttpServletRequest request) {
		final RedirectView redirectView = new RedirectView();

		String ciphertext_hex = request.getParameter("msg");
		if (ciphertext_hex == null) {
			logger.warn("no msg parameter");
			redirectView.setUrl("/authenticationError");
		    return redirectView;
		}

		final String KEY = oidcAttributes.getIdpKey();
		final String IV = oidcAttributes.getIdpIv();

		try {
			final byte [] ciphertext = Hex.decodeHex(ciphertext_hex.toCharArray());
		
			// on décrypte la requête
			final PaddedBufferedBlockCipher aes = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), new PKCS7Padding());
			final CipherParameters ivAndKey = new ParametersWithIV(new KeyParameter(Hex.decodeHex(KEY.toCharArray())), Hex.decodeHex(IV.toCharArray()));
			aes.init(false, ivAndKey);
			final int minSize = aes.getOutputSize(ciphertext.length);
			final byte [] outBuf = new byte[minSize];
			int length1 = aes.processBytes(ciphertext, 0, ciphertext.length, outBuf, 0);
			int length2 = aes.doFinal(outBuf, length1);
			final String plaintext = new String(outBuf, 0, length1 + length2, Charset.forName("UTF-8"));
			final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			OIDCAuthenticationToken oidcauth = (OIDCAuthenticationToken) auth;

			// on récupère l'identité de l'utilisateur
			final String info = oidcauth.getUserInfo().toJson().toString();
			final byte [] info_plaintext = info.getBytes();

			// on encrypte l'identité de l'utilisateur
			aes.init(true, ivAndKey);
			final byte [] inputBuf = new byte[aes.getOutputSize(info_plaintext.length)];
			length1 = aes.processBytes(info_plaintext, 0, info_plaintext.length, inputBuf, 0);
			length2 = aes.doFinal(inputBuf, length1);
			final byte [] info_ciphertext = ArrayUtils.subarray(inputBuf, 0, length1 + length2);
			final String info_ciphertext_hex = new String(Hex.encodeHex(info_ciphertext));

			// on construit l'URL vers laquelle on redirige le navigateur en ajoutant l'identité chiffrée à l'URL de callback indiquée dans la requête
			final String return_url;
			URL url = new URL(plaintext);
			if (url.getQuery() == null || url.getQuery().isEmpty())
				return_url = plaintext + "?info=" + info_ciphertext_hex;
			else
				return_url = plaintext + "&info=" + info_ciphertext_hex;

			// on redirige l'utilisateur
			redirectView.setUrl(return_url);
			return redirectView;
		} catch (final Exception ex) {
			logger.warn(ex.getMessage());
			redirectView.setUrl("/authenticationError");
		    return redirectView;
		}
	}
}
