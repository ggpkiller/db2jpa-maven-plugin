package com.ggp;

import com.mysql.jdbc.Driver;
import com.mysql.jdbc.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

import javax.swing.*;
import java.io.*;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("all")
@Mojo(name = "generate")
public class Generator extends AbstractMojo {

    @Parameter(property = "baseDir")
    private String baseDir;
    @Parameter(property = "packageName")
    private String packageName;
    @Parameter(property = "jdbcUrl")
    private String jdbcUrl;
    @Parameter(property = "userName")
    private String userName;
    @Parameter(property = "password")
    private String password;
    @Parameter(property = "overWrite", defaultValue = "false")
    private boolean overWrite;

    private final String table = null;

    private final String charset = "UTF-8";

    private final Set<String> keywords = new HashSet<>(Arrays.asList(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "strictfp",
            "short",
            "static",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null"
    ));

    public void execute() throws MojoExecutionException, MojoFailureException {

        String absoluteDir = baseDir.endsWith("/") ? baseDir + packageName.replaceAll("\\.", "/") + "/"
                : baseDir + "/" + packageName.replaceAll("\\.", "/") + "/";
        System.out.println("逆向输出路径：" + absoluteDir);
        System.out.println("package包名：" + packageName);
        File dirFile = new File(absoluteDir);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }

        try {
            Class.forName(Driver.class.getName());
        } catch (ClassNotFoundException e) {
            System.err.println("JDBC 驱动加载失败 : \n" + e.getMessage());
        }

        Connection conn = null;
        DatabaseMetaData metaData;
        ResultSet tableRs = null;
        try {
            conn = DriverManager.getConnection(
                    jdbcUrl,
                    userName, password);

            //获取元数据
            metaData = conn.getMetaData();
            //获取所有表名
            tableRs = metaData.getTables(userName, null, table, new String[]{"TABLE"});
            line:
            while (tableRs.next()) {
                Set<String> imports = new HashSet<>();
                String tableName = tableRs.getString("TABLE_NAME");
                String javaName = convert2JavaName(tableName, "class");
                File javaFile = new File(absoluteDir + javaName + ".java");
                if (!overWrite) {
                    //不覆盖
                    if (javaFile.exists()) {
                        System.out.println("文件 " + javaName + ".java 存在，已忽略");
                        continue line;
                    }
                }

                ResultSet pkRs = metaData.getPrimaryKeys(userName, null, tableName);
                pkRs.next();
                String pkName = pkRs.getString("COLUMN_NAME");
                pkRs.close();
                StringBuilder head = new StringBuilder();
                head.append("package ").append(packageName).append(";");

                StringBuilder field = new StringBuilder();
                StringBuilder getterAndSetter = new StringBuilder();

                //获取表的所有列数据
                ResultSet colRs = metaData.getColumns(userName, null, tableName, null);
                while (colRs.next()) {
                    String colName = colRs.getString("COLUMN_NAME");
                    String fieldName = convert2JavaName(colName, "field");
                    fieldName = checkFieldKeyword(fieldName) ? fieldName + "s" : fieldName;
                    imports.add("javax.persistence.*");
                    String remarks = colRs.getString("REMARKS");
                    String isAutoincrement = colRs.getString("IS_AUTOINCREMENT");
                    String nullable = colRs.getString("NULLABLE");
                    String defVal = colRs.getString("COLUMN_DEF");
                    int sqlDataType = colRs.getInt("DATA_TYPE");

//                    System.out.println(colName + "=" + sqlDataType);
                    //注释
                    if (!StringUtils.isNullOrEmpty(remarks)) {
                        field.append("/**\n*").append(remarks).append("\n*/\n");
                    }


                    if (colName.equals(pkName)) {
                        field.append("@Id");
                        if ("YES".equals(isAutoincrement)) {
                            field.append("@GeneratedValue(strategy = GenerationType.IDENTITY)");
                        }
                    }
                    field.append("@Column(name = \"");
                    field.append(colName);
                    field.append("\"");
                    if ("0".equals(nullable)) {
                        field.append(",nullable = false");
                    }
                    int len = -1;
                    int precision = -1;
                    int scale = -1;
                    switch (sqlDataType) {
                        case Types.VARCHAR:
                        case Types.CHAR:
                            len = colRs.getInt("COLUMN_SIZE");
                            break;
                        case Types.NUMERIC:
                        case Types.DECIMAL:
                        case Types.DOUBLE:
                        case Types.FLOAT:
                            precision = colRs.getInt("COLUMN_SIZE");
                            scale = colRs.getInt("DECIMAL_DIGITS");
                            break;
                    }
                    field.append(", columnDefinition = \"");


                    switch (sqlDataType) {
                        case Types.VARCHAR:
                            field.append("VARCHAR(").append(len).append(")");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT '").append(defVal).append("'");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("String ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("String", fieldName));
                            getterAndSetter.append(setterFunction("String", fieldName));
                            break;
                        case Types.INTEGER:
                            field.append("INTEGER");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT ").append(defVal);
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Integer ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Integer", fieldName));
                            getterAndSetter.append(setterFunction("Integer", fieldName));
                            break;
                        case Types.TINYINT:
                        case Types.BIT:
                            field.append("TINYINT");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT ").append(defVal).append(" ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Integer ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Integer", fieldName));
                            getterAndSetter.append(setterFunction("Integer", fieldName));
                            break;
                        case Types.SMALLINT:
                            field.append("SMALLINT");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append("DEFAULT ").append(defVal).append(" ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Integer ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Integer", fieldName));
                            getterAndSetter.append(setterFunction("Integer", fieldName));
                            break;
                        case Types.BIGINT:
                            field.append("BIGINT");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT ").append(defVal).append(" ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Long ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Long", fieldName));
                            getterAndSetter.append(setterFunction("Long", fieldName));
                            break;
                        case Types.CHAR:

                            field.append("CHAR(").append(len).append(") ");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT '").append(defVal).append("' ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("String ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("String", fieldName));
                            getterAndSetter.append(setterFunction("String", fieldName));
                            break;
                        case Types.DATE:
                            field.append("DATE");
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Date ").append(fieldName).append(";");
                            imports.add("java.util.Date");
                            getterAndSetter.append(getterFunction("Date", fieldName));
                            getterAndSetter.append(setterFunction("Date", fieldName));
                            break;
                        case Types.TIME:
                            field.append("TIME");
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Date ").append(fieldName).append(";");
                            imports.add("java.util.Date");
                            getterAndSetter.append(getterFunction("Date", fieldName));
                            getterAndSetter.append(setterFunction("Date", fieldName));
                            break;
                        case Types.TIMESTAMP:
                            field.append("TIMESTAMP");
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Date ").append(fieldName).append(";");
                            imports.add("java.util.Date");
                            getterAndSetter.append(getterFunction("Date", fieldName));
                            getterAndSetter.append(setterFunction("Date", fieldName));
                            break;
                        case Types.DECIMAL:
                            field.append("DECIMAL(").append(precision).append(",").append(scale).append(") ");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT ").append(defVal).append(" ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Double ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Double", fieldName));
                            getterAndSetter.append(setterFunction("Double", fieldName));
                            break;
                        case Types.DOUBLE:
                            field.append("DOUBLE(").append(precision).append(",").append(scale).append(")");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT ").append(defVal).append(" ");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("Double ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("Double", fieldName));
                            getterAndSetter.append(setterFunction("Double", fieldName));
                            break;
                        case Types.LONGVARCHAR:
                        case Types.LONGNVARCHAR:
                            field.append("TEXT");
                            if (!StringUtils.isNullOrEmpty(defVal)) {
                                field.append(" DEFAULT '").append(defVal).append("'");
                            }
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("'");
                            }
                            field.append("\")");
                            field.append("private ").append("String ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("String", fieldName));
                            getterAndSetter.append(setterFunction("String", fieldName));
                            break;
                        case Types.LONGVARBINARY:
                        case Types.VARBINARY:
                        case Types.BINARY:
                            field.append("BLOB");
                            if (!StringUtils.isNullOrEmpty(remarks)) {
                                field.append(" COMMENT '").append(remarks).append("' ");
                            }
                            field.append("\")");

                            field.append("@Lob@Basic(fetch = FetchType.LAZY)");
                            field.append("private ").append("String ").append(fieldName).append(";");
                            getterAndSetter.append(getterFunction("String", fieldName));
                            getterAndSetter.append(setterFunction("String", fieldName));
                            break;
                    }


                }
                imports.forEach(v -> head.append("import ").append(v).append(";"));
                head.append("@Entity@Table(name = \"").append(tableName).append("\")");
                head.append("public class ").append(javaName).append(" {");
                head.append(field).append(getterAndSetter).append("}");
                String finalContent = tryFormat(head.toString());
                if (StringUtils.isNullOrEmpty(finalContent)) {
                    System.err.println("@@@@Java代码生成失败：  " + javaName + ".java");
                    System.out.println(head.toString());
                    continue line;
                }
                saveEntityFile(absoluteDir, javaName, finalContent);
                colRs.close();
            }

        } catch (SQLException e) {
            System.err.println("数据库操作失败 ： \n" + e.getMessage());
        } finally {
            try {
                if (Objects.nonNull(tableRs)) {
                    tableRs.close();
                }
                if (Objects.nonNull(conn)) {
                    conn.close();
                }
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
        }
    }


    private void saveEntityFile(String dirPath, String javaName, String content) {

        PrintWriter out = null;
        try {
            out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(dirPath + javaName + ".java"), Charset.forName(charset)));

            out.write(content);

            out.flush();

            System.out.println("生成文件成功： " + javaName + ".java√");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (Objects.nonNull(out)) {
                out.close();
            }
        }
    }

    private String convert2JavaName(String tableName, String type) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String src = tableName;
        if ("class".equals(type)) {
            bos.write(Character.toUpperCase(src.charAt(0)));
            src = src.substring(1);
        }


        AtomicBoolean foundTip = new AtomicBoolean(false);
        src.chars().forEach(value -> {
            if (value != '_') {
                if (foundTip.get()) {
                    bos.write(Character.toUpperCase(value));
                    foundTip.set(false);
                } else {
                    bos.write(value);
                }
            } else {
                foundTip.set(true);
            }
        });
        return new String(bos.toByteArray());
    }

    private StringBuilder getterFunction(String typeName, String fieldName) {
        char[] cs = new char[fieldName.length()];
        cs[0] = Character.toUpperCase(fieldName.charAt(0));
        System.arraycopy(fieldName.toCharArray(), 1, cs, 1, fieldName.length() - 1);
        String newFiledName = new String(cs);
        StringBuilder func = new StringBuilder();
        func.append("public ").append(typeName).append(" get")
                .append(newFiledName).append("()").append("{")
                .append("return this.").append(fieldName).append(";").append("}");
        return func;
    }

    private StringBuilder setterFunction(String typeName, String fieldName) {
        char[] cs = new char[fieldName.length()];
        cs[0] = Character.toUpperCase(fieldName.charAt(0));
        System.arraycopy(fieldName.toCharArray(), 1, cs, 1, fieldName.length() - 1);
        String newFiledName = new String(cs);
        StringBuilder func = new StringBuilder();
        func.append("public void set").append(newFiledName).append("(").append(typeName)
                .append(" ").append(fieldName)
                .append(")").append("{").append("this.").append(fieldName).append(" = ").append(fieldName).append(";}");
        return func;
    }

    private String tryFormat(String code) {
        Map m = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        m.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_8);
        m.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
        m.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
        m.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, "160");
        m.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.TAB);
        IDocument doc;
        try {
            CodeFormatter codeFormatter = ToolFactory.createCodeFormatter(m);
            TextEdit textEdit = codeFormatter.format(CodeFormatter.K_UNKNOWN, code, 0, code.length(), 0, null);
            if (textEdit != null) {
                doc = new Document(code);
                textEdit.apply(doc);
                return doc.get();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean checkFieldKeyword(String fieldName) {
        return keywords.contains(fieldName);
    }
}
