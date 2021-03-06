	package es.gob.jmulticard.card.fnmt.ceres;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.security.auth.callback.PasswordCallback;

import es.gob.jmulticard.CryptoHelper;
import es.gob.jmulticard.HexUtils;
import es.gob.jmulticard.apdu.CommandApdu;
import es.gob.jmulticard.apdu.ResponseApdu;
import es.gob.jmulticard.apdu.ceres.CeresVerifyApduCommand;
import es.gob.jmulticard.apdu.ceres.LoadDataApduCommand;
import es.gob.jmulticard.apdu.ceres.SignDataApduCommand;
import es.gob.jmulticard.apdu.connection.ApduConnection;
import es.gob.jmulticard.apdu.connection.ApduConnectionException;
import es.gob.jmulticard.apdu.iso7816eight.EnvelopeDataApduCommand;
import es.gob.jmulticard.asn1.Asn1Exception;
import es.gob.jmulticard.asn1.TlvException;
import es.gob.jmulticard.asn1.der.pkcs1.DigestInfo;
import es.gob.jmulticard.card.BadPinException;
import es.gob.jmulticard.card.CryptoCard;
import es.gob.jmulticard.card.CryptoCardException;
import es.gob.jmulticard.card.InvalidCardException;
import es.gob.jmulticard.card.Location;
import es.gob.jmulticard.card.PrivateKeyReference;
import es.gob.jmulticard.card.fnmt.ceres.asn1.CeresCdf;
import es.gob.jmulticard.card.fnmt.ceres.asn1.CeresPrKdf;
import es.gob.jmulticard.card.iso7816eight.Iso7816EightCard;
import es.gob.jmulticard.card.iso7816four.FileNotFoundException;
import es.gob.jmulticard.card.iso7816four.Iso7816FourCardException;

/** Tarjeta FNMT-RCM CERES.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s */
public final class Ceres extends Iso7816EightCard implements CryptoCard {

	private static final byte CLA = (byte) 0x00;

	private final CryptoHelper cryptoHelper;

    private static final Location CDF_LOCATION = new Location("50156004"); //$NON-NLS-1$
    private static final Location PRKDF_LOCATION = new Location("50156001"); //$NON-NLS-1$

    /** Nombre del Fichero Maestro. */
    private static final String MASTER_FILE_NAME = "Master.File"; //$NON-NLS-1$

    /** Octeto que identifica una verificaci&oacute;n fallida del PIN */
    private final static byte ERROR_PIN_SW1 = (byte) 0x63;

    private Map<String, X509Certificate> certs;
    private Map<String, Byte> keys;

    private final PasswordCallback passwordCallback;

    private boolean authenticated = false;

	/** Construye una clase que representa una tarjeta FNMT-RCM CERES.
	 * @param conn Conexi&oacute;n con la tarjeta.
	 * @param pwc <i>PasswordCallback</i> para obtener el PIN de la tarjeta.
	 * @param ch Clase para la realizaci&oacute;n de las huellas digitales del <i>DigestInfo</i>.
	 * @throws ApduConnectionException Si hay problemas con la conexi&oacute;n proporcionada.
	 * @throws InvalidCardException Si la tarjeta conectada no es una FNMT-RCM CERES.
	 */
	public Ceres(final ApduConnection conn,
			     final PasswordCallback pwc,
			     final CryptoHelper ch) throws ApduConnectionException, InvalidCardException {
		super(CLA, conn);
		if (ch == null) {
			throw new IllegalArgumentException("El CryptoHelper no puede ser nulo"); //$NON-NLS-1$
		}
		getConnection().open();
		try {
			preload();
		}
		catch (final Exception e) {
			throw new ApduConnectionException("Error cargando las estructuras iniciales de la tarjeta: " + e, e); //$NON-NLS-1$
		}
		this.cryptoHelper = ch;
		this.passwordCallback = pwc;
	}

	private void preload() throws ApduConnectionException,
	                              Iso7816FourCardException,
	                              IOException,
	                              CertificateException,
	                              Asn1Exception,
	                              TlvException {
		// Cargamos el CDF
        final CeresCdf cdf = new CeresCdf();
        cdf.setDerValue(selectFileByLocationAndRead(CDF_LOCATION));

        // Leemos los certificados segun las rutas del CDF
        final CertificateFactory cf = CertificateFactory.getInstance("X.509"); //$NON-NLS-1$
        this.certs = new LinkedHashMap<String, X509Certificate>(cdf.getCertificateCount());
        for (int i=0; i<cdf.getCertificateCount(); i++) {
        	final Location l = new Location(cdf.getCertificatePath(i).replace("\\", "").trim()); //$NON-NLS-1$ //$NON-NLS-2$
        	final X509Certificate cert = (X509Certificate) cf.generateCertificate(
    			new ByteArrayInputStream(
					deflate(
						selectFileByLocationAndRead(l)
					)
				)
			);
        	this.certs.put(i + " " + cert.getSerialNumber(), cert); //$NON-NLS-1$
        }

        System.out.println(cdf.toString());

        final CeresPrKdf prkdf = new CeresPrKdf();
        final byte[] prkdfValue =  selectFileByLocationAndRead(PRKDF_LOCATION);
        prkdf.setDerValue(prkdfValue);

        System.out.println(prkdf.toString());

        if (prkdf.getKeyCount() != this.certs.size()) {
        	throw new IllegalStateException(
    			"El numero de claves de la tarjeta (" + prkdf.getKeyCount() + ") no coincide con el de certificados (" + this.certs.size() + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			);
        }
        this.keys = new LinkedHashMap<String, Byte>(this.certs.size());
        final String[] aliases = getAliases();
        for (int i=0; i<this.certs.size(); i++) {
        	this.keys.put(
    			aliases[i],
				Byte.valueOf(prkdf.getKeyReference(i))
			);
        }
	}

	@Override
	public String[] getAliases() throws CryptoCardException {
		return this.certs.keySet().toArray(new String[0]);
	}

	@Override
	public X509Certificate getCertificate(final String alias) throws CryptoCardException, BadPinException {
		return this.certs.get(alias);
	}

	@Override
	public PrivateKeyReference getPrivateKey(final String alias) throws CryptoCardException {
		return new CeresPrivateKeyReference(
			this.keys.get(alias).byteValue(),
			((RSAPublicKey)this.certs.get(alias).getPublicKey()).getModulus().bitLength()
		);
	}

	@Override
	public byte[] sign(final byte[] data, final String algorithm, final PrivateKeyReference keyRef) throws CryptoCardException, BadPinException {

		if (data == null) {
			throw new CryptoCardException("Los datos a firmar no pueden ser nulos"); //$NON-NLS-1$
		}

		if (keyRef == null) {
			throw new IllegalArgumentException("La clave privada no puede ser nula"); //$NON-NLS-1$
		}
		if (!(keyRef instanceof CeresPrivateKeyReference)) {
			throw new IllegalArgumentException(
				"La clave proporcionada debe ser de tipo CeresPrivateKeyReference, pero se ha recibido de tipo " + keyRef.getClass().getName() //$NON-NLS-1$
			);
		}
		final CeresPrivateKeyReference ceresPrivateKey = (CeresPrivateKeyReference) keyRef;

		// Pedimos el PIN si no se ha pedido antes
		if (!this.authenticated) {
			try {
				verifyPin(this.passwordCallback);
				this.authenticated = true;
			}
			catch (final ApduConnectionException e1) {
				throw new CryptoCardException("Error en la verificacion de PIN: " + e1, e1); //$NON-NLS-1$
			}
		}

		final byte[] digestInfo;
		try {
			digestInfo = DigestInfo.encode(algorithm, data, this.cryptoHelper);
		}
		catch(final Exception e) {
			throw new CryptoCardException(
				"Erros creando el DigestInfo para la firma con el algoritmo " + algorithm + ": " + e, e //$NON-NLS-1$ //$NON-NLS-2$
			);
		}

		loadData(ceresPrivateKey.getKeyBitSize(), digestInfo);

		final ResponseApdu res;

		final CommandApdu cmd = new SignDataApduCommand(
			ceresPrivateKey.getKeyReference(), // Referencia
			ceresPrivateKey.getKeyBitSize()    // Tamano en bits de la clave
		);
		try {
			res = sendArbitraryApdu(cmd);
		}
		catch (final Exception e) {
			throw new CryptoCardException("Error firmando los datos: " + e, e); //$NON-NLS-1$
		}
		if (!res.isOk()) {
			throw new CryptoCardException("No se han podido firmar los datos. Respuesta: " + HexUtils.hexify(res.getBytes(), true)); //$NON-NLS-1$
		}
		return res.getData();
	}

	private void loadData(final int keyBitSize, final byte[] digestInfo) throws CryptoCardException {
		final byte[] paddedData;
		try {
			paddedData = CryptoHelper.addPkcs1PaddingForPrivateKeyOperation(
				digestInfo,
				keyBitSize
			);
		}
		catch (final Exception e1) {
			throw new CryptoCardException(
				"Error realizando el relleno PKCS#1 de los datos a firmar: " + e1, //$NON-NLS-1$
				e1
			);
		}

		ResponseApdu res;

		// Si la clave es de 1024 la carga se puede hacer en una unica APDU
		if (keyBitSize < 2048) {
			try {
				res = sendArbitraryApdu(new LoadDataApduCommand(paddedData));
			}
			catch (final Exception e) {
				throw new CryptoCardException("Error enviando los datos a firmar a la tarjeta: " + e, e); //$NON-NLS-1$
			}
			if (!res.isOk()) {
				throw new CryptoCardException("No se han podido enviar los datos a firmar a la tarjeta. Respuesta: " + HexUtils.hexify(res.getBytes(), true)); //$NON-NLS-1$
			}
		}
		// Pero si es de 2048 hacen falta dos APDU, envolviendo la APDU de carga de datos
		else if (keyBitSize == 2048) {

			final byte[] envelopedLoadDataApdu = new byte[] {
				(byte) 0x90, (byte) 0x58, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00
			};

			// La primera APDU carga 0xFF octetos (254)
			byte[] data = new byte[255];
			System.arraycopy(envelopedLoadDataApdu, 0, data, 0, envelopedLoadDataApdu.length);
			System.arraycopy(paddedData, 0, data, envelopedLoadDataApdu.length, 255 - envelopedLoadDataApdu.length);

			try {
				res = sendArbitraryApdu(new EnvelopeDataApduCommand(data));
			}
			catch (final Exception e) {
				throw new CryptoCardException("Error en el segundo envio a la tarjeta de los datos a firmar: " + e, e); //$NON-NLS-1$
			}
			if (!res.isOk()) {
				throw new CryptoCardException(
					"No se han podido enviar (segunda tanda) los datos a firmar a la tarjeta. Respuesta: " + HexUtils.hexify(res.getBytes(), true) //$NON-NLS-1$
				);
			}

			// La segunda APDU es de 0x08 octetos (8)
			data = new byte[8];
			System.arraycopy(paddedData, 255 - envelopedLoadDataApdu.length, data, 0, 8);

			try {
				res = sendArbitraryApdu(new EnvelopeDataApduCommand(data));
			}
			catch (final Exception e) {
				throw new CryptoCardException("Error en el primer envio a la tarjeta de los datos a firmar: " + e, e); //$NON-NLS-1$
			}
			if (!res.isOk()) {
				throw new CryptoCardException(
					"No se han podido enviar (primera tanda) los datos a firmar a la tarjeta. Respuesta: " + HexUtils.hexify(res.getBytes(), true) //$NON-NLS-1$
				);
			}

		}

		else {
			throw new IllegalArgumentException("Solo se soportan claves de 2048 o menos bits"); //$NON-NLS-1$
		}

	}

	@Override
	protected void selectMasterFile() throws ApduConnectionException, FileNotFoundException, Iso7816FourCardException {
		selectFileByName(MASTER_FILE_NAME);
	}

	@Override
	public void verifyPin(final PasswordCallback pinPc) throws ApduConnectionException, BadPinException {
		final CommandApdu chv = new CeresVerifyApduCommand(CLA, pinPc);
		final ResponseApdu verifyResponse = sendArbitraryApdu(chv);
        if (!verifyResponse.isOk()) {
            if (verifyResponse.getStatusWord().getMsb() == ERROR_PIN_SW1) {
            	throw new BadPinException(verifyResponse.getStatusWord().getLsb() - (byte) 0xC0);
            }
        }
	}

	@Override
	public String getCardName() {
		return "FNMT-RCM CERES"; //$NON-NLS-1$
	}

    /** Descomprime un certificado contenido en la tarjeta CERES.
     * @param compressedCertificate Certificado comprimido en ZIP a partir del 9 octeto.
     * @return Certificado codificado.
     * @throws IOException Cuando se produce un error en la descompresi&oacute;n del certificado. */
    private static byte[] deflate(final byte[] compressedCertificate) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final Inflater decompressor = new Inflater();
        decompressor.setInput(compressedCertificate, 8, compressedCertificate.length - 8);
        final byte[] buf = new byte[1024];
        try {
            // Descomprimimos los datos
            while (!decompressor.finished()) {
                final int count = decompressor.inflate(buf);
                if (count == 0) {
                    throw new DataFormatException();
                }
                buffer.write(buf, 0, count);
            }
            // Obtenemos los datos descomprimidos
            return buffer.toByteArray();
        }
        catch (final DataFormatException ex) {
            throw new IOException("Error al descomprimir el certificado: " + ex, ex); //$NON-NLS-1$
        }
    }

}
