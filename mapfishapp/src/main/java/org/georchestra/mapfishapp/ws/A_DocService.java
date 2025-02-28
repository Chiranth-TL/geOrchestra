/*
 * Copyright (C) 2009 by the geOrchestra PSC
 *
 * This file is part of geOrchestra.
 *
 * geOrchestra is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * geOrchestra is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * geOrchestra.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.georchestra.mapfishapp.ws;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Random;

import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.SAXException;

/**
 * This service is the basic template to handle the storage and the loading of a
 * file Some methods can be overridden to provide treatments specific to a file
 * extension.
 *
 * @author yoann buch - yoann.buch@gmail.com
 *
 */

public abstract class A_DocService {

    protected static final Log LOG = LogFactory.getLog(A_DocService.class.getPackage().getName());

    /**
     * Document prefix helping to differentiate documents among other OS temporary
     * files
     */
    protected static final String DOC_PREFIX = "geodoc";

    /**
     * File extension.
     */
    protected String _fileExtension;

    /**
     * Db connection pool (shared between services).
     */
    protected DataSource pgPool;

    /**
     * Sets pgPool (used for testing).
     *
     * @param pgPool
     */
    public void setPgPool(DataSource pgPool) {
        this.pgPool = pgPool;
    }

    /**
     * MIME type.
     */
    private String _MIMEType;

    /**
     * File content. Can be altered
     */
    protected String _content;

    /**
     * File name. Can be altered otherwise default name is kept (the one generated
     * by OS)
     */
    protected String _name;

    /**
     * old files can be read from the configured directory
     */
    private String _tempDirectory;

    /**
     * Creates the temporal directory if it doesn't exist and set the path
     */
    private void setTempDirectory(final String tempDirectory) {

        File t = new File(tempDirectory);
        if (!t.exists()) {
            boolean succeed = t.mkdirs();

            if (!succeed) {
                LOG.error("cannot create the directory: " + tempDirectory);
            }
        }
        _tempDirectory = tempDirectory;

    }

    /*
     * ========================Public
     * Methods====================================================
     */

    /**
     * Subclasses have to provide their file extension name and MIME type
     *
     * @param fileExtension
     * @param MIMEType
     * @param docTempDirectory
     */
    public A_DocService(final String fileExtension, final String MIMEType, final String docTempDirectory,
            DataSource pgpool) {
        _fileExtension = fileExtension;
        _MIMEType = MIMEType;
        pgPool = pgpool;
        setTempDirectory(docTempDirectory);
    }

    private String indentData(String data) throws JDOMException, IOException {
        SAXBuilder sb = new SAXBuilder();
        sb.setExpandEntities(false);
        Document doc = sb.build(new StringReader(data));
        XMLOutputter xop = new XMLOutputter();
        xop.setFormat(Format.getPrettyFormat());
        return xop.outputString(doc);
    }

    /**
     * Store the given data
     * 
     * @param data     raw data to be stored
     * @param username the current user name or empty string if anonymous (correct
     *                 ?)
     * @return file name
     * @throws DocServiceException
     */
    protected String saveData(final String data, final String username) throws DocServiceException {

        _content = data;

        // Tries to indent the document before saving it
        try {
            _content = indentData(data);

        } catch (Exception e1) {
            // actually give up (if malformed, or if another issue
            // has been caught), keeping the old behaviour.
            _content = data;
        }

        // actions to take before saving data
        preSave();

        // compute md5: not on data, because it would not be unique across users, but on
        // a random string
        String hash = null;
        try {
            // hash = MD5(_content);
            Random r = new Random();
            Double d = r.nextDouble();
            hash = MD5(_content + d.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // extract standard
        String standard = _fileExtension.substring(1);

        // write data to Db
        try (Connection connection = pgPool.getConnection()) {
            String sql = "INSERT INTO mapfishapp.geodocs (username, standard, raw_file_content, file_hash) VALUES (?,?,?,?);";
            try (PreparedStatement st = connection.prepareStatement(sql)) {
                st.setString(1, username);
                st.setString(2, standard);
                st.setString(3, _content);
                st.setString(4, hash);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.error(e);
            throw new RuntimeException(e);
        }
        return DOC_PREFIX + hash + _fileExtension;
    }

    /**
     * Load the file corresponding to the file name in the service. Content can be
     * accessed via getContent, name via getName, and MIME type via getMIMEType
     * 
     * @param fileName file name
     * @throws DocServiceException
     */
    public void loadFile(final String fileName) throws DocServiceException {
        // check first if data exists somewhere (db / file)
        try {
            if (!isFileExist(fileName)) {
                throw new DocServiceException("Requested file does not exist.", HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // default, file name will be the one generated by OS
        _name = fileName;

        // load file content
        _content = loadContent(fileName);

        // actions to take after loading the content
        postLoad();
    }

    /**
     * Return a JSON array with decriptions of all files for specified standard.
     * Descriptions may include some specific fields based on standard.
     * 
     * @param username username to filter geodoc.
     * @return a JSON array with following keys : hash, created_at, last_access,
     *         access_count and maybe other keys based on standard
     * @throws Exception if problems occurs when retrieving data from database
     */
    public JSONArray listFiles(String username) throws Exception {
        JSONArray res = new JSONArray();

        try (Connection connection = pgPool.getConnection()) {
            String sql = "SELECT file_hash, created_at, last_access, access_count, raw_file_content "
                    + "FROM mapfishapp.geodocs " + "WHERE standard = ? AND username = ? " + "ORDER BY created_at DESC";
            try (PreparedStatement st = connection.prepareStatement(sql)) {
                st.setString(1, _fileExtension.substring(1));
                st.setString(2, username);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        JSONObject entry = new JSONObject();

                        // Add common fields, all standards have these fields
                        entry.put("hash", rs.getString("file_hash"));
                        entry.put("created_at", rs.getString("created_at"));
                        entry.put("last_access", rs.getString("last_access"));
                        entry.put("access_count", rs.getString("access_count"));

                        // Add standard specific fields
                        JSONObject standardSpecificEntry;
                        try {
                            standardSpecificEntry = this
                                    .extractsStandardSpecificEntries(rs.getBinaryStream("raw_file_content"));
                        } catch (Exception e) {
                            LOG.error("Unable to parse the document [hash: " + rs.getString("file_hash")
                                    + "]. Skipping.");
                            continue;
                        }
                        Iterator<String> it = standardSpecificEntry.keys();
                        while (it.hasNext()) {
                            String field = it.next();
                            entry.put(field, standardSpecificEntry.get(field));
                        }
                        res.put(entry);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error(e);
        }
        return res;
    }

    public void deleteFile(String filename, String username) throws Exception {
        try (Connection connection = pgPool.getConnection()) {
            String sql = "DELETE FROM mapfishapp.geodocs WHERE file_hash = ? AND username = ?";
            try (PreparedStatement st = connection.prepareStatement(sql)) {
                st.setString(1, filename);
                st.setString(2, username);

                if (st.executeUpdate() != 1) {
                    throw new SQLException(
                            "Unable to find record with file_hash : " + filename + " and username : " + username);
                }
            }
        } catch (SQLException e) {
            LOG.error(e);
        }
    }

    /*
     * ========================Accessor
     * Methods====================================================
     */

    /**
     * Get the MIME type
     * 
     * @return String MIME type
     */
    public String getMIMEType() {
        return _MIMEType;
    }

    /**
     * Get the file content. Should be called once loadFile has been called.
     * 
     * @return String file content
     */
    public String getContent() {
        if (_content == null) {
            throw new RuntimeException("_content is null. Should be called after loadFile");
        }
        return _content;
    }

    /**
     * Get the file name (contains file extension). Should be called once loadFile
     * has been called.
     * 
     * @return String file name
     */
    public String getName() {
        if (_name == null) {
            throw new RuntimeException("_name is null. Should be called after loadFile");
        }
        return _name;
    }

    /*
     * ========================Protected Methods - Variable
     * algorithms==============================================
     */

    /**
     * Must be override to take actions before the data are saved. <br />
     * Examples: valid data format or integrity, interpret or transform data.
     * 
     * @throws DocServiceException
     */
    protected void preSave() throws DocServiceException {
    }

    /**
     * Must be override to take actions once the file is load in memory <br />
     * Examples: parse the file to get the real file name
     * 
     * @throws DocServiceException
     */
    protected void postLoad() throws DocServiceException {
    }

    /**
     * Provide a method to its subclasses to determine if their content is valid
     * based on a xsd schema
     * 
     * @param schemaURL
     * @return true: valid; false: not valid. No use to expect this return value. If
     *         the document is not valid a DocServiceException is thrown
     * @throws DocServiceException
     */
    protected boolean isDocumentValid(final String schemaURL) throws DocServiceException {
        try {

            InputStream dataToValid = new ByteArrayInputStream(getContent().getBytes("UTF-8"));

            // lookup a factory for the W3C XML Schema language
            SchemaFactory factory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");

            // get the schema online.
            Schema schema = factory.newSchema(new URL(schemaURL));

            // prepare source to valid by the validator based on the schema
            Source source = new StreamSource(dataToValid);
            Validator validator = schema.newValidator();

            // check if doc is valid
            validator.validate(source);
            return true;
        } catch (SAXException ex) {
            // occurs when validation errors happen
            throw new DocServiceException("File is not valid. " + ex.getMessage(),
                    HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        } catch (IOException e) {
            LOG.error("Error while checking validity of the document", e);
        }
        return false;
    }

    /**
     * This method will extracts specific fields from geodoc and return a JSON
     * object with only those fields. This JSON will be merged with the one
     * extracted by listFiles() method. This method should be overrided.
     * 
     * @param rawDoc stream connected to geodoc
     * @return a JSON object containing only fields dedicated to this geodoc
     *         standard
     * @throws Exception if geodoc cannot be parsed
     */
    protected JSONObject extractsStandardSpecificEntries(InputStream rawDoc) throws Exception {
        return new JSONObject();
    }

    /*
     * =====================Private Methods - Common to every
     * DocService=========================================
     */

    /**
     * Returns a md5 hash from a given string
     * 
     * @param text input string
     * @return md5 hash
     */
    private String MD5(final String text) throws NoSuchAlgorithmException {
        byte[] toHash = text.getBytes();
        byte[] MD5Digest = null;
        StringBuilder hashString = new StringBuilder();

        MessageDigest algo = MessageDigest.getInstance("MD5");
        algo.reset();
        algo.update(toHash);
        MD5Digest = algo.digest();

        for (int i = 0; i < MD5Digest.length; i++) {
            String hex = Integer.toHexString(MD5Digest[i]);
            if (hex.length() == 1) {
                hashString.append('0');
                hashString.append(hex.charAt(hex.length() - 1));
            } else {
                hashString.append(hex.substring(hex.length() - 2));
            }
        }
        return hashString.toString();
    }

    /**
     * Check that data exists in db under provided hash
     * 
     * @param fileName eg geodoc1694e3cc580768d5125816b574915e97.wmc or
     *                 geodoc\d{19}.wmc
     * @return true: exists, false: not exists
     */
    private boolean isFileExist(final String fileName) throws SQLException, RuntimeException {
        // test fileName to know if file is stored in db or file.
        if (fileName.length() == 4 + 32 + DOC_PREFIX.length()) {
            // newest database storage
            int count = 0;
            try (Connection connection = pgPool.getConnection()) {
                String sql = "SELECT count(*)::integer from mapfishapp.geodocs WHERE file_hash = ?;";
                try (PreparedStatement st = connection.prepareStatement(sql)) {
                    st.setString(1, fileName.substring(DOC_PREFIX.length(), DOC_PREFIX.length() + 32));
                    try (ResultSet rs = st.executeQuery()) {
                        if (rs.next()) {
                            count = rs.getInt(1);
                        }
                    }
                }
            } catch (SQLException e) {
                LOG.error(e);
                throw new RuntimeException(e);
            }

            return count > 0;

        } else { // plain old "file" storage

            // file was stored previously in a known place
            File dir = new File(_tempDirectory);

            if (!dir.exists()) {
                throw new RuntimeException(_tempDirectory + " directory not found");
            }

            // prepare filter to get the right file
            FilenameFilter filter = new FilenameFilter() {
                public boolean accept(File dir, String name) {

                    return fileName.equals(name);
                }
            };

            // get file thanks to the previous filter
            String[] fileList = dir.list(filter);

            return fileList.length == 1;
        }
    }

    /**
     * Get file content of the given file stored in DIR_PATH
     * 
     * @param fileName file name
     * @return file content
     */
    private String loadContent(final String fileName) {
        String content = "";
        // test fileName to know if the file is stored in db or file.
        if (fileName.length() == 4 + 32 + DOC_PREFIX.length()) {
            String hash = fileName.substring(DOC_PREFIX.length(), DOC_PREFIX.length() + 32);
            // newest database storage
            try (Connection connection = pgPool.getConnection()) {
                String sql = "SELECT raw_file_content from mapfishapp.geodocs WHERE file_hash = ?;";
                try (PreparedStatement st = connection.prepareStatement(sql)) {
                    st.setString(1, hash);
                    try (ResultSet rs = st.executeQuery()) {
                        if (rs.next()) {
                            content = rs.getString(1);
                        }
                    }
                }
                sql = "UPDATE mapfishapp.geodocs set last_access = now() , access_count = access_count + 1 WHERE file_hash = ?;";
                try (PreparedStatement st = connection.prepareStatement(sql)) {
                    // now that we have loaded the content, update the metadata fields
                    st.setString(1, hash);
                    st.executeUpdate();
                }
            } catch (SQLException e) {
                LOG.error(e);
                throw new RuntimeException(e);
            }
        } else {
            // plain old "file" storage
            File file = new File(_tempDirectory + File.separatorChar + fileName);

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);

                // get file size
                long fileSize = file.length();
                if (fileSize > Integer.MAX_VALUE) {
                    throw new IOException("File is too big");
                }

                // allocate necessary memory to store content
                byte[] bytes = new byte[(int) fileSize];

                // read the content in the byte array
                int offset = 0;
                int numRead = 0;
                while (offset < bytes.length && (numRead = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                    offset += numRead;
                }

                // Ensure all the bytes have been read
                if (offset < bytes.length) {
                    throw new IOException("Could not completely read file " + file.getName());
                }

                // return the file content
                content = new String(bytes);

            } catch (FileNotFoundException fnfExc) {
                LOG.error("file not found", fnfExc);
            } catch (IOException ioExc) {
                LOG.error("Error accessing file", ioExc);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        LOG.error(e);
                    }
                }
            }
        }
        return content;
    }

}
