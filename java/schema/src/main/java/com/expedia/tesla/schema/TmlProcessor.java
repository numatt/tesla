/*******************************************************************************
 * Copyright (c) 2014 Expedia Inc.
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
 *******************************************************************************/
package com.expedia.tesla.schema;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.SAXException;

import com.expedia.tesla.SchemaVersion;
import com.expedia.tesla.schema.tml.v2.Tml;

/**
 * TML file is an user friendly Tesla schema file format. It will be
 * preprocessed to before Tesla compiler convert it into Tesla schema object
 * model. Following steps will be taken when a TML file was preprocessed.
 * <p>
 * <li>Read and preprocess all imported TML files recusively.</li>
 * <li>Scan all user type definitions and put them into a local user type list.
 * All types defined in the current TML can be refered with short name.</li>
 * <li>Resolve all short names to full name.</li>
 * <p>
 * 
 * @author Yunfei Zuo (yzuo@expedia.com)
 * 
 */
abstract class TmlProcessor {

	private static final Set<String> PRIMITIVE_NAMES = new HashSet<String>();
	private static final Pattern TYPE_ID_KEYWORDS_PATTERN = Pattern.compile(",|<|>|\\[|\\]|array|map|nullable|reference|poly");
	private static JAXBContext jaxbContext;

	static {
		for (Primitive t : Primitive.ALL_PRIMITIVES) {
			PRIMITIVE_NAMES.add(t.getName());
		}
	}

	/**
	 * Read, preprocess and parse TML file. Get all user types defined in the
	 * current TML and its included TML files.
	 * 
	 * @param path
	 *            The path of TML file.
	 * 
	 * @return A list of objects represent either class ({@link Tml.Types.Class}
	 *         or enum ({@link Tml.Types.Enum}.
	 * 
	 * @throws Exception
	 */
	public static Collection<Object> load(String path) throws TeslaSchemaException,
			IOException {
		File file = new File(path);
		TmlGraph graph = new TmlGraph(file);
		preprocess(graph, file);
		return getAllUserTypes(graph, file);
	}

	public static void save(Schema schema, OutputStream os) throws IOException, JAXBException {
		List<Object> userTypes = new ArrayList<Object>();
		for (Type t : schema.getTypes()) {
			if (t.isClass()) {
				Class clzz = (Class) t;
				Tml.Types.Class tmlClss = new Tml.Types.Class();

				tmlClss.setName(clzz.getName());
				tmlClss.setDescription(clzz.getDescription());

				if (!clzz.getBaseTypeNames().isEmpty()) {
					Iterator<String> itr = clzz.getBaseTypeNames().iterator();
					StringBuilder sb = new StringBuilder();
					sb.append(itr.next());
					while (itr.hasNext()) {
						sb.append(",");
						sb.append(itr.next());
					}
					tmlClss.setExtends(sb.toString());
				}

				for (Field f : clzz.getFields()) {
					Tml.Types.Class.Field tf = new Tml.Types.Class.Field();
					tf.setName(f.getName());
					tf.setType(f.getType().getTypeId().replace("<", "(")
							.replace(">", ")"));
					tf.setDescription(f.getDescription());
					for (String attr : f.getAttributes().keySet()) {
						tf.getOtherAttributes().put(new QName(attr),
								f.getAttribute(attr));
					}
					tmlClss.getField().add(tf);
				}
				userTypes.add(tmlClss);
			} else if (t.isEnum()) {
				Enum e = (Enum) t;
				Tml.Types.Enum enm = new Tml.Types.Enum();

				enm.setName(e.getName());
				enm.setDescription(e.getDescription());

				for (EnumEntry ev : e.getEntries()) {
					Tml.Types.Enum.Entry entry = new Tml.Types.Enum.Entry();
					entry.setName(ev.getName());
					entry.setValue(ev.getValue());
					entry.setDescription(ev.getDescription());
					enm.getEntry().add(entry);
				}
				userTypes.add(enm);
			}
		}
		SchemaVersion ver = schema.getVersion();
		save(userTypes, os, ver.getName(), ver.getVersionNumber());
	}

	public static void save(List<Object> userTypes, OutputStream os,
			String versionName, int versionNumber) throws IOException, JAXBException {
		Tml tml = new Tml();
		Tml.Types types = new Tml.Types();
		types.getClazzOrEnum().addAll(userTypes);
		tml.setTypes(types);
		Tml.Version ver = new Tml.Version();
		ver.setName(versionName);
		ver.setNumber(versionNumber);
		tml.setVersion(ver);
		TmlGraph.marshallTml(tml, os);
	}

	private static String array(String elementType, int rank) {
		assert (rank >= 0);
		switch (rank) {
		case 0:
			return elementType;
		case 1:
			return String.format("array<%s>", elementType);
		default:
			return String.format("array<%s>", array(elementType, rank - 1));
		}
	}

	private static String getTypeName(Object classOrEnum) {
		if (classOrEnum instanceof Tml.Types.Class) {
			return ((Tml.Types.Class) classOrEnum).getName();
		} else if (classOrEnum instanceof Tml.Types.Enum) {
			return ((Tml.Types.Enum) classOrEnum).getName();
		} else {
			throw new IllegalArgumentException();
		}
	}

	private static boolean isPrimitive(String name) {
		return PRIMITIVE_NAMES.contains(name.toLowerCase());
	}

	private static void preprocess(TmlGraph graph, File file)
			throws TeslaSchemaException, IOException {
		// Convert short type declaration names to full names
		dfs(graph, file, new TmlVisitor() {
			@Override
			public void visit(TmlNode tml) {
				String namespace = tml.getTml().getNamespace() == null ? null
						: tml.getTml().getNamespace().getName();
				for (Object t : tml.getTml().getTypes().getClazzOrEnum()) {
					if (t instanceof Tml.Types.Class) {
						Tml.Types.Class clss = (Tml.Types.Class) t;
						clss.setName(toFullName(clss.getName(), namespace));
					} else if (t instanceof Tml.Types.Enum) {
						Tml.Types.Enum enm = (Tml.Types.Enum) t;
						enm.setName(toFullName(enm.getName(), namespace));
					}
				}
			}
		});

		// Resolve type references
		dfs(graph, file, new TmlVisitor() {
			@Override
			public void visit(TmlNode tml) throws TeslaSchemaException {
				String namespace = tml.getTml().getNamespace() == null ? null
						: tml.getTml().getNamespace().getName();
				List<Object> types = tml.getTml().getTypes().getClazzOrEnum();
				for (Object t : types) {
					if (t instanceof Tml.Types.Class) {
						Tml.Types.Class clss = (Tml.Types.Class) t;

						// Base type references
						if (clss.getExtends() != null
								&& !clss.getExtends().isEmpty()) {
							String[] baseTypeNames = clss.getExtends().split(
									"\\s*,\\s*");
							String fullNames = "";
							for (int i = 0; i < baseTypeNames.length; i++) {
								fullNames += fullNames.isEmpty() ? "" : ",";
								fullNames += resolveTypeReferenceToTypeId(
										normalizeTypeId(baseTypeNames[i]),
										namespace, types, null); // TODO: dump
																	// name
																	// symbols
							}
							clss.setExtends(fullNames);
						}

						// Field type references
						for (Tml.Types.Class.Field field : clss.getField()) {
							if (field.getDisplayname() == null
									|| field.getDisplayname().isEmpty()) {
								field.setDisplayname(field.getName());
							}
							field.setType(normalizeTypeId(field.getType()));
							field.setType(resolveTypeReferenceToTypeId(
									field.getType(), namespace, types, null)); // TODO:
																				// dump
																				// name
																				// symbols
							if (field.getRank() != null && field.getRank() > 0) {
								field.setType(array(field.getType(),
										field.getRank()));
								field.setRank((short) 0);
							}
							if (field.isReference() != null
									&& field.isReference()) {
								field.setType(String.format("reference<%s>",
										field.getType()));
								field.setReference(false);
							}
							if (field.isNullable() != null
									&& field.isNullable()) {
								field.setType(String.format("nullable<%s>",
										field.getType()));
								field.setNullable(false);
							}
						}
					}
				}
			}
		});
	}

	private static String resolveTypeReferenceToTypeId(String name,
			String defaultNamespace, List<Object> currentUserTypes,
			List<Object> importedUserTypes) throws TeslaSchemaException {
		
		// If this is a type id format, break down the string into token stream and resolve type name tokens.
		String[] tokens = name.split(Schema.TYPE_ID_TOKENIZER_PATTERN);
		if (tokens.length >= 3) {
			String id = "";
			for (int i = 0; i < tokens.length; i++) {
				if (!TYPE_ID_KEYWORDS_PATTERN.matcher(tokens[i]).matches()) {
					String token = resolveTypeReferenceToTypeId(tokens[i], defaultNamespace, currentUserTypes, 
							importedUserTypes);
					
					// In case the type reference is something like class<MyClass>, the resolved token will looks like
					// class<namespace.MyClass>. In order to avoid result of class<class<namespace.MyClass>>, we will 
					// use only the resolved full class or enum name here.
					if (i >= 2 && i < tokens.length - 1 && tokens[i - 1].equals("<") && tokens[i + 1].equals(">")) {
						if (tokens[i - 2].equals("class") ) {
							token = token.substring("class<".length(), token.length() - 1);
						} else if (tokens[i - 2].equals("enum")) {
							token = token.substring("enum<".length(), token.length() - 1);
						}
					}
					id += token;
				} else {
					id += tokens[i];
				}
			}
			return id;
		}

		// 1. primitive
		if (isPrimitive(name)) {
			return normalizePrimitiveName(name);
		}

		// 2. search local types by full name.
		String fn = toFullName(name, defaultNamespace);
		for (Object t : currentUserTypes) {
			if (t instanceof Tml.Types.Class
					&& fn.equals(((Tml.Types.Class) t).getName())) {
				return Class.nameToId(((Tml.Types.Class) t).getName());
			} else if (t instanceof Tml.Types.Enum
					&& fn.equals(((Tml.Types.Enum) t).getName())) {
				return Enum.nameToId(((Tml.Types.Enum) t).getName());
			}
		}

		// 3. try to search dumped imported types
		String id = null;
		if (importedUserTypes != null) {
			fn = null;
			for (Object t : importedUserTypes) {
				if (toShortName(getTypeName(t)).equals(name)) {
					if (fn == null) {
						fn = getTypeName(t);
						if (t instanceof Tml.Types.Class) {
							id = Class.nameToId(fn);
						} else if (t instanceof Tml.Types.Enum) {
							id = Enum.nameToId(fn);
						} else {
							assert false : "Invalid type";
						}
					} else {
						throw new TeslaSchemaException(String.format(
								"Ambiguous name '%s'. Could be %s or %s.",
								name, fn, getTypeName(t)));
					}
				}
			}
			if (id != null) {
				return id;
			}
		}

		return name;
	}

	private static String toFullName(String name, String defaultNamespace) {
		if (name.matches(".+\\..+") || isPrimitive(name)) { // already full name
			return name;
		}

		if (defaultNamespace != null && !defaultNamespace.isEmpty()) {
			return String.format("%s.%s", defaultNamespace, name);
		}

		return name;
	}

	private static String toShortName(String name) {
		return name.replaceFirst("^.+\\.", "");
	}

	private static String normalizePrimitiveName(String name) {
		if (isPrimitive(name)) {
			return name.toLowerCase();
		}
		return name;
	}

	private static String normalizeTypeId(String id) {
		return id.replaceAll("\\s", "").replaceAll("\\(", "<")
				.replaceAll("\\)", ">");
	}

	public static Tml unmarshalTml(String path) throws JAXBException {
		if (jaxbContext == null) {
			jaxbContext = JAXBContext.newInstance(Tml.class);
		}
		Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
		return (Tml) unmarshaller.unmarshal(new File(path));
	}

	public static long getSchemaHash(String filename)
			throws TeslaSchemaException, IOException {
		try (InputStream is = new FileInputStream(filename)) {
			return getSchemaHash(is);
		}
	}

	private static long getSchemaHash(InputStream is)
			throws TeslaSchemaException, IOException {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("SHA1");
		} catch (NoSuchAlgorithmException ex) {
			throw new TeslaSchemaException(ex.getMessage(), ex);
		}

		DigestInputStream dis = new DigestInputStream(is, md);
		while (dis.read() != -1)
			;
		byte[] digest = md.digest();
		return (new BigInteger(digest)).longValue();
	}

	private static void dfs(TmlGraph graph, File root, TmlVisitor visitor)
			throws TeslaSchemaException, IOException {
		dfs(graph, root, new HashSet<File>(), visitor);
	}

	private static void dfs(TmlGraph graph, File current, Set<File> visited,
			TmlVisitor visitor) throws TeslaSchemaException, IOException {
		if (visited.contains(current)) {
			return;
		}

		for (TmlNode imp : graph.imports(current)) {
			imp.acceptVisitor(visitor);
		}

		TmlNode tn = graph.findVertex(current);
		tn.acceptVisitor(visitor);
		visited.add(current);
	}

	private static List<Object> getAllUserTypes(TmlGraph graph, File root)
			throws TeslaSchemaException, IOException {
		final List<Object> userTypes = new ArrayList<Object>();
		dfs(graph, root, new TmlVisitor() {
			@Override
			public void visit(TmlNode tml) {
				userTypes.addAll(tml.getTml().getTypes().getClazzOrEnum());
			}
		});
		return userTypes;
	}

	public static Schema build(String path) throws TeslaSchemaException, IOException {
		Collection<Object> types = TmlProcessor.load(path);
		long hash = TmlProcessor.getSchemaHash(path);
		Tml.Version ver = TmlGraph.unmarshallTml(new File(path)).getVersion();
		return TmlProcessor.build(types,
				new SchemaVersion(hash, ver.getNumber() == null ? (short) 0
						: ver.getNumber().shortValue(), ver.getName(), path));
	}

	public static Schema build(Collection<Object> types, SchemaVersion ver) throws TeslaSchemaException {
		Schema.SchemaBuilder schemaBuilder = new Schema.SchemaBuilder();

		for (Object type : types) {
			// build classes, without fields.
			if (type instanceof Tml.Types.Class) {
				Tml.Types.Class c = (Tml.Types.Class) type;
				String id = Class.nameToId(c.getName());
				schemaBuilder.addType(id);
				
			} else if (type instanceof Tml.Types.Enum) {
				// build enums, without entries.
				Tml.Types.Enum e = (Tml.Types.Enum) type;
				String id = Enum.nameToId(e.getName());
				schemaBuilder.addType(id);
			}
		}

		for (Object type : types) {
			if (type instanceof Tml.Types.Class) {
				Tml.Types.Class c = (Tml.Types.Class) type;
				
				// define class with base classes, fields and description.
				List<Class> bases = new ArrayList<Class>();
				if (c.getExtends() != null && !c.getExtends().isEmpty()) {
					for (String b : c.getExtends().split(",")) {
						Class base = (Class) schemaBuilder.addType(b);
						bases.add(base);
					}
				}
				
				List<Field> fields = new ArrayList<Field>();
				for (Tml.Types.Class.Field f : c.getField()) {
					Map<String, String> attributes = new HashMap<String, String>();
					for (Map.Entry<QName, String> attr : f.getOtherAttributes().entrySet()) {
						attributes.put(attr.getKey().getLocalPart(),
								attr.getValue());
					}

					Field field = new Field(f.getName(), f.getDisplayname(), schemaBuilder.addType(f.getType()),
							attributes, f.getDescription());
					fields.add(field);
				}
				
				Class cls = (Class) schemaBuilder.findType(Class.nameToId(c.getName()));
				cls.define(bases, fields, c.getDescription());
			} else if (type instanceof Tml.Types.Enum) {
				// define enum with entries and description.
				Tml.Types.Enum e = (Tml.Types.Enum) type;
				Enum enm = (Enum) schemaBuilder.findType(Enum.nameToId(e.getName()));

				Collection<EnumEntry> entries = new ArrayList<EnumEntry>();
				for (Tml.Types.Enum.Entry tmlEntry : e.getEntry()) {
					entries.add(new EnumEntry(tmlEntry.getName(), tmlEntry.getValue(), tmlEntry.getDescription()));
				}

				enm.define(entries, e.getDescription());
			}
		}

		schemaBuilder.setVersion(ver);
		schemaBuilder.validate();
		return schemaBuilder;

	}
}

class TmlNode {
	private File file;
	private SchemaVersion version;
	private Tml tml;

	public TmlNode(File file, SchemaVersion version, Tml tml) {
		this.file = file;
		this.version = version;
		this.tml = tml;
	}

	public File getFile() {
		return file;
	}

	public SchemaVersion getVersion() {
		return version;
	}

	public Tml getTml() {
		return tml;
	}

	public void acceptVisitor(TmlVisitor visitor) throws TeslaSchemaException,
			IOException {
		visitor.visit(this);
	}
}

interface TmlVisitor {
	void visit(TmlNode tml) throws TeslaSchemaException, IOException;
}

class TmlGraph {
	private final Map<File, TmlNode> vertices = new HashMap<File, TmlNode>();
	private final Map<File, List<File>> edges = new HashMap<File, List<File>>();
	private static JAXBContext jaxbContext;

	static {
		try {
			jaxbContext = JAXBContext.newInstance(Tml.class);
		} catch (JAXBException e) {
			throw new RuntimeException("Failed to create JAXB context.", e);
		}
	}

	public TmlGraph(File file) throws TeslaSchemaException, IOException {
		build(file);
	}

	private TmlNode build(File file) throws TeslaSchemaException, IOException {
		if (hasVertex(file)) {
			return findVertex(file);
		}

		TmlNode current = readTml(file);
		addVertex(current);

		for (Tml.Import imp : current.getTml().getImport()) {
			addEdge(current, build(new File(imp.getFile())));
		}
		return current;
	}

	private void addEdge(TmlNode current, TmlNode imported) {
		edges.get(current.getFile()).add(imported.getFile());
	}

	private void addVertex(TmlNode v) {
		File cf = v.getFile();

		assert (!hasVertex(cf));
		assert (!vertices.containsKey(cf));
		assert (!edges.containsKey(cf));

		vertices.put(cf, v);
		edges.put(cf, new ArrayList<File>());
	}

	public TmlNode findVertex(File file) {
		return vertices.get(file);
	}

	public boolean hasVertex(File file) {
		return findVertex(file) != null;
	}

	public Collection<TmlNode> imports(File file) {
		assert (hasVertex(file));
		Collection<TmlNode> imported = new ArrayList<TmlNode>();
		for (File f : edges.get(file)) {
			assert (hasVertex(f));
			imported.add(findVertex(f));
		}
		return imported;
	}

	private static TmlNode readTml(File file) throws TeslaSchemaException,
			IOException {
		validateTml(file);
		Tml tml = unmarshallTml(file);
		SchemaVersion version = new SchemaVersion(0L, tml.getVersion()
				.getNumber() == null ? (short) 0 : tml.getVersion().getNumber()
				.shortValue(), tml.getVersion().getName(),
				file.getAbsolutePath());
		return new TmlNode(file, version, tml);
	}

	private static void validateTml(File file) throws TeslaSchemaException,
			IOException {
		URL schemaFile = TmlGraph.class.getClassLoader().getResource(
				"tml-v2.xsd");
		Source xmlFile = new StreamSource(file);
		try {
			Validator validator = SchemaFactory
					.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
					.newSchema(schemaFile).newValidator();
			validator.validate(xmlFile);
		} catch (SAXException ex) {
			throw new TeslaSchemaException("Invalid TML: "
					+ file.getCanonicalPath(), ex);
		}
	}

	public static Tml unmarshallTml(File file) throws TeslaSchemaException,
			IOException {
		Unmarshaller unmarshaller;
		try {
			unmarshaller = jaxbContext.createUnmarshaller();
			return (Tml) unmarshaller.unmarshal(file);
		} catch (JAXBException ex) {
			throw new TeslaSchemaException("Failed to unmarshall TML file "
					+ file.toString(), ex);
		}
	}

	public static void marshallTml(Tml tml, OutputStream os)
			throws IOException, JAXBException {
		Marshaller marshaller = jaxbContext.createMarshaller();
		marshaller.marshal(tml, os);
	}
}