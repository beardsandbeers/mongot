package com.xgen.proto;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import com.xgen.mongot.proto.bson.ProtoArray;
import com.xgen.mongot.proto.bson.ProtoBinaryData;
import com.xgen.mongot.proto.bson.ProtoBinarySubType;
import com.xgen.mongot.proto.bson.ProtoDateTime;
import com.xgen.mongot.proto.bson.ProtoDbPointer;
import com.xgen.mongot.proto.bson.ProtoDecimal128;
import com.xgen.mongot.proto.bson.ProtoDocument;
import com.xgen.mongot.proto.bson.ProtoJavaScript;
import com.xgen.mongot.proto.bson.ProtoJavaScriptWithScope;
import com.xgen.mongot.proto.bson.ProtoObjectId;
import com.xgen.mongot.proto.bson.ProtoRegularExpression;
import com.xgen.mongot.proto.bson.ProtoSymbol;
import com.xgen.mongot.proto.bson.ProtoTimestamp;
import com.xgen.mongot.proto.bson.ProtoValue;
import com.xgen.mongot.proto.bson.ProtoValueless;
import com.xgen.mongot.util.BsonUtils;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDbPointer;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonJavaScript;
import org.bson.BsonJavaScriptWithScope;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonSymbol;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      GenBsonProtoTest.ProtoToBson.class,
      GenBsonProtoTest.BsonToProto.class,
      GenBsonProtoTest.BsonToProtoException.class,
      GenBsonProtoTest.ExtraTests.class
    })
public class GenBsonProtoTest {
  public static class TestCase {
    public final String name;
    public final BsonValue bson;
    public final BsonValueMessage proto;

    public TestCase(String name, BsonValue bson, BsonValueMessage proto) {
      this.name = name;
      this.bson = bson;
      this.proto = proto;
    }

    public TestCase(String name, BsonValue bson, BsonValueMessage.BsonValueBuilder builder) {
      this(name, bson, builder.build());
    }

    @Override
    public String toString() {
      return this.name;
    }
  }

  private static final ObjectId OBJECT_ID = new ObjectId();

  public static List<TestCase> common_cases() {
    return List.of(
        new TestCase(
            "GreetingMessage",
            new BsonDocument("message", new BsonString("Hello, World!")),
            Greeting.newBuilder().setMessage("Hello, World!")),
        new TestCase(
            "GenericDocGreeting",
            new BsonDocument("message", new BsonString("Hello, World!")),
            ProtoDocument.newBuilder()
                .addField("message", ProtoValue.newBuilder().setString("Hello, World!"))),
        new TestCase(
            "GenericDocBooleanValue",
            new BsonDocument("value", BsonBoolean.valueOf(true)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.newBuilder().setBool(true))),
        new TestCase(
            "GenericDocInt32Value",
            new BsonDocument("value", new BsonInt32(32)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.newBuilder().setInt32(32))),
        new TestCase(
            "GenericDocInt64Value",
            new BsonDocument("value", new BsonInt64(64)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.newBuilder().setInt64(64))),
        new TestCase(
            "GenericDocDoubleValue",
            new BsonDocument("value", new BsonDouble(3.14)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.newBuilder().setDouble(3.14))),
        new TestCase(
            "GenericDocDateTimeValue",
            new BsonDocument("value", new BsonDateTime(42)),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setUtcDatetime(42))),
        new TestCase(
            "GenericDocTimestampValue",
            new BsonDocument("value", new BsonTimestamp(84)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.newBuilder().setTimestamp(84))),
        new TestCase(
            "GenericDocStringValue",
            new BsonDocument("value", new BsonString("this is a test")),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setString("this is a test"))),
        new TestCase(
            "GenericDocBinaryValue",
            new BsonDocument(
                "value",
                new BsonBinary(
                    (byte) 128, ByteString.copyFromUtf8("this is a test").toByteArray())),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setBinary(
                            ProtoBinaryData.newBuilder()
                                .setSubType(ProtoBinarySubType.USER_DEFINED)
                                .setData(ByteString.copyFromUtf8("this is a test"))))),
        new TestCase(
            "GenericDocDocValue",
            new BsonDocument(
                "value",
                new BsonDocument()
                    .append("y", BsonBoolean.valueOf(true))
                    .append("n", BsonBoolean.valueOf(false))),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setDocument(
                            ProtoDocument.newBuilder()
                                .addField("y", ProtoValue.newBuilder().setBool(true))
                                .addField("n", ProtoValue.newBuilder().setBool(false))))),
        new TestCase(
            "GenericDocArrayValue",
            new BsonDocument(
                "value",
                new BsonArray(List.of(BsonBoolean.valueOf(false), BsonBoolean.valueOf(true)))),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setArray(
                            ProtoArray.newBuilder()
                                .addValue(ProtoValue.newBuilder().setBool(false))
                                .addValue(ProtoValue.newBuilder().setBool(true))))),
        new TestCase(
            "GenericDocObjectIdValue",
            new BsonDocument("value", new BsonObjectId(OBJECT_ID)),
            ProtoDocument.newBuilder().addField("value", ProtoValue.parseBsonFrom(OBJECT_ID))),
        new TestCase(
            "GenericDocNullValue",
            new BsonDocument("value", BsonNull.VALUE),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setValueless(ProtoValueless.NULL))),
        new TestCase(
            "GenericDocMinKeyValue",
            new BsonDocument("value", BsonUtils.MIN_KEY),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setValueless(ProtoValueless.MIN_KEY))),
        new TestCase(
            "GenericDocMaxKeyValue",
            new BsonDocument("value", BsonUtils.MAX_KEY),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setValueless(ProtoValueless.MAX_KEY))),
        new TestCase(
            "GenericDocUndefinedValue",
            new BsonDocument("value", new BsonUndefined()),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setValueless(ProtoValueless.UNDEFINED))),
        new TestCase(
            "GenericDocRegularExpressionValue",
            new BsonDocument("value", new BsonRegularExpression("star.*", "i")),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setRegularExpression(
                            ProtoRegularExpression.newBuilder()
                                .setPattern("star.*")
                                .setOptions("i")))),
        new TestCase(
            "GenericDocJavaScriptValue",
            new BsonDocument("value", new BsonJavaScript("this is a test")),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setJavaScript("this is a test"))),
        new TestCase(
            "GenericDocDecimal128Value",
            new BsonDocument("value", new BsonDecimal128(Decimal128.fromIEEE754BIDEncoding(13, 7))),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setDecimal128(
                            ByteString.copyFrom(
                                new byte[] {7, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0})))),
        new TestCase(
            "GenericDocDbPointerValue",
            new BsonDocument("value", new BsonDbPointer("proto", OBJECT_ID)),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setDbPointer(
                            ProtoDbPointer.newBuilder()
                                .setNamespace("proto")
                                .setObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID))))),
        new TestCase(
            "GenericDocSymbolValue",
            new BsonDocument("value", new BsonSymbol("this is a test")),
            ProtoDocument.newBuilder()
                .addField("value", ProtoValue.newBuilder().setSymbol("this is a test"))),
        new TestCase(
            "GenericDocJavaScriptWithScopeValue",
            new BsonDocument(
                "value",
                new BsonJavaScriptWithScope(
                    "this is a test", new BsonDocument("y", BsonBoolean.valueOf(true)))),
            ProtoDocument.newBuilder()
                .addField(
                    "value",
                    ProtoValue.newBuilder()
                        .setJavaScriptWithScope(
                            ProtoJavaScriptWithScope.newBuilder()
                                .setCode("this is a test")
                                .setScope(
                                    ProtoDocument.newBuilder()
                                        .addField("y", ProtoValue.newBuilder().setBool(true)))))),
        new TestCase(
            "TestSingles",
            new BsonDocument()
                .append("textString", new BsonString("hi"))
                .append("byteString", new BsonBinary(new byte[] {1, 2, 3}))
                .append("intSingle", new BsonInt32(-32))
                .append("intDouble", new BsonInt64(-64L << 32))
                .append("floatSingle", new BsonDouble(0.125))
                .append("floatDouble", new BsonDouble(3.14159))
                .append("uintSingle", new BsonInt32(32))
                .append("uintDouble", new BsonInt64(64L << 32))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTextString("hi")
                .setByteString(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .setIntSingle(-32)
                .setIntDouble(-64L << 32)
                .setFloatSingle(0.125f)
                .setFloatDouble(3.14159)
                .setUintSingle(32)
                .setUintDouble(64L << 32)),
        new TestCase(
            "TestNestedObject",
            new BsonDocument()
                .append("nestedObject", new BsonDocument("textString", new BsonString("hello")))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setNestedObject(TestMessage.Nested.newBuilder().setTextString("hello"))),
        new TestCase(
            "EnumsTest",
            new BsonDocument()
                .append("topLevelEnum", new BsonString("two"))
                .append("status", new BsonString("fileNotFound"))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTopLevelEnum(TopLevelEnum.TWO)
                .setStatus(TestMessage.Status.FILE_NOT_FOUND)),
        new TestCase(
            "TestRepeatedFields",
            new BsonDocument()
                .append(
                    "textStrings",
                    new BsonArray(List.of(new BsonString("oh"), new BsonString("hai"))))
                .append(
                    "byteStrings",
                    new BsonArray(
                        List.of(
                            new BsonBinary(new byte[] {1, 2, 3}),
                            new BsonBinary(new byte[] {4, 5, 6}))))
                .append(
                    "floatDoubles",
                    new BsonArray(
                        List.of(new BsonDouble(0.5), new BsonDouble(0.25), new BsonDouble(0.125))))
                .append(
                    "nestedObjects",
                    new BsonArray(
                        List.of(new BsonDocument("textString", new BsonString("nested hai")))))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .addTextStrings("oh")
                .addTextStrings("hai")
                .addByteStrings(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .addByteStrings(ByteString.copyFrom(new byte[] {4, 5, 6}))
                .addNestedObjects(TestMessage.Nested.newBuilder().setTextString("nested hai"))
                .addFloatDoubles(0.5)
                .addFloatDoubles(0.25)
                .addFloatDoubles(0.125)),
        new TestCase(
            "TestAllowSingleValue1",
            new BsonDocument()
                .append("intListOrValue", new BsonInt32(17))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder().addIntListOrValue(17).setScalarWithoutPresence(0)),
        new TestCase(
            "TestAllowSingleValue2",
            new BsonDocument()
                .append(
                    "intListOrValue", new BsonArray(List.of(new BsonInt32(17), new BsonInt32(19))))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .addIntListOrValue(17)
                .addIntListOrValue(19)
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TestMapStringToInt",
            new BsonDocument()
                .append("scalarWithoutPresence", new BsonInt32(0))
                .append(
                    "mapStringToInt",
                    new BsonDocument()
                        .append("foo", new BsonInt32(7))
                        .append("bar", new BsonInt32(11))),
            TestMessage.newBuilder()
                .putMapStringToInt("foo", 7)
                .putMapStringToInt("bar", 11)
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TestMapStringToMessage",
            new BsonDocument()
                .append("scalarWithoutPresence", new BsonInt32(0))
                .append(
                    "mapStringToMessage",
                    new BsonDocument()
                        .append("foo", new BsonDocument("message", new BsonString("hello")))
                        .append("bar", new BsonDocument("message", new BsonString("hoi")))),
            TestMessage.newBuilder()
                .putMapStringToMessage("foo", Greeting.newBuilder().setMessage("hello").build())
                .putMapStringToMessage("bar", Greeting.newBuilder().setMessage("hoi").build())
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TestScalarWithoutPresence",
            new BsonDocument("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()),
        new TestCase(
            "TestJsonName",
            new BsonDocument()
                .append("realGoodText", new BsonString("hoi"))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder().setJsonNamed("hoi")),
        new TestCase(
            "WKDocField",
            new BsonDocument("doc", new BsonDocument("bool", BsonBoolean.valueOf(true))),
            WKTMessage.newBuilder()
                .setDoc(
                    ProtoDocument.newBuilder()
                        .addField(
                            ProtoDocument.Field.newBuilder()
                                .setName("bool")
                                .setValue(ProtoValue.newBuilder().setBool(true))))),
        new TestCase(
            "WKArrayField",
            new BsonDocument(
                "array", new BsonArray(List.of(BsonBoolean.valueOf(true), new BsonInt32(42)))),
            WKTMessage.newBuilder()
                .setArray(
                    ProtoArray.newBuilder()
                        .addValue(ProtoValue.newBuilder().setBool(true))
                        .addValue(ProtoValue.newBuilder().setInt32(42)))),
        new TestCase(
            "WKValueField",
            new BsonDocument("value", BsonNull.VALUE),
            WKTMessage.newBuilder()
                .setValue(ProtoValue.newBuilder().setValueless(ProtoValueless.NULL))),
        new TestCase(
            "WKDateTime",
            new BsonDocument("dateTime", new BsonDateTime(42)),
            WKTMessage.newBuilder().setDateTime(ProtoDateTime.newBuilder().setRep(42))),
        new TestCase(
            "WKTimestamp",
            new BsonDocument("timestamp", new BsonTimestamp(42)),
            WKTMessage.newBuilder().setTimestamp(ProtoTimestamp.newBuilder().setRep(42))),
        new TestCase(
            "WKBinaryField",
            new BsonDocument(
                "binary", new BsonBinary(BsonBinarySubType.USER_DEFINED, new byte[] {0x10, 0x10})),
            WKTMessage.newBuilder()
                .setBinary(
                    ProtoBinaryData.newBuilder()
                        .setSubType(ProtoBinarySubType.USER_DEFINED)
                        .setData(ByteString.copyFrom(new byte[] {0x10, 0x10})))),
        new TestCase(
            "WKObjectIdField",
            new BsonDocument("objectId", new BsonObjectId(OBJECT_ID)),
            WKTMessage.newBuilder().setObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID))),
        new TestCase(
            "WKRegularExpressionField",
            new BsonDocument("regex", new BsonRegularExpression("star.*", "i")),
            WKTMessage.newBuilder()
                .setRegex(
                    ProtoRegularExpression.newBuilder().setPattern("star.*").setOptions("i"))),
        new TestCase(
            "WKDecimal128Field",
            new BsonDocument(
                "decimal128", new BsonDecimal128(Decimal128.fromIEEE754BIDEncoding(13, 7))),
            WKTMessage.newBuilder()
                .setDecimal128(
                    ProtoDecimal128.newBuilder()
                        .setRep(
                            ByteString.copyFrom(
                                new byte[] {7, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0})))),
        new TestCase(
            "WKDbPointerField",
            new BsonDocument("dbPointer", new BsonDbPointer("test", OBJECT_ID)),
            WKTMessage.newBuilder()
                .setDbPointer(
                    ProtoDbPointer.newBuilder()
                        .setNamespace("test")
                        .setObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID)))),
        new TestCase(
            "WKSymbol",
            new BsonDocument("symbol", new BsonSymbol("sym")),
            WKTMessage.newBuilder().setSymbol(ProtoSymbol.newBuilder().setRep("sym"))),
        new TestCase(
            "WKJavaScript",
            new BsonDocument("javaScript", new BsonJavaScript("code")),
            WKTMessage.newBuilder().setJavaScript(ProtoJavaScript.newBuilder().setCode("code"))),
        new TestCase(
            "WKDocJavaScriptWithScopeField",
            new BsonDocument(
                "javaScriptWithScope",
                new BsonJavaScriptWithScope(
                    "this is a test", new BsonDocument("y", BsonBoolean.valueOf(true)))),
            WKTMessage.newBuilder()
                .setJavaScriptWithScope(
                    ProtoJavaScriptWithScope.newBuilder()
                        .setCode("this is a test")
                        .setScope(
                            ProtoDocument.newBuilder()
                                .addField("y", ProtoValue.newBuilder().setBool(true))))),
        new TestCase(
            "DiscriminatedUnion",
            new BsonDocument()
                .append("type", new BsonString("fooBar"))
                .append("enabled", BsonBoolean.valueOf(true))
                .append("fooBarMessage", new BsonString("foobar"))
                .append("baseProperty", new BsonInt32(0)),
            DiscriminatedUnionMessage.newBuilder()
                .setFooBar(
                    DiscriminatedUnionMessage.FooBar.newBuilder()
                        .setEnabled(true)
                        .setFooBarMessage("foobar")
                        .build())),
        new TestCase(
            "DiscriminatedUnionNoneSet",
            new BsonDocument("baseProperty", new BsonInt32(7)),
            DiscriminatedUnionMessage.newBuilder().setBaseProperty(7)),
        new TestCase(
            "DiscriminatedUnionJsonName",
            new BsonDocument()
                .append("type", new BsonString("batFoo"))
                .append("enabled", BsonBoolean.valueOf(true))
                .append("baseProperty", new BsonInt32(0)),
            DiscriminatedUnionMessage.newBuilder()
                .setFooBat(DiscriminatedUnionMessage.FooBat.newBuilder().setEnabled(true).build())),
        new TestCase(
            "DiscriminationUnionMember",
            new BsonDocument()
                .append(
                    "union",
                    new BsonDocument()
                        .append("type", new BsonString("fooBar"))
                        .append("fooBarMessage", new BsonString("hoi"))
                        .append("baseProperty", new BsonInt32(0)))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setUnion(
                    DiscriminatedUnionMessage.newBuilder()
                        .setFooBar(
                            DiscriminatedUnionMessage.FooBar.newBuilder()
                                .setFooBarMessage("hoi")))),
        new TestCase(
            "TypeUnionBoolMember",
            new BsonDocument()
                .append("typeUnion", BsonBoolean.valueOf(true))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTypeUnion(TypeUnionValueMessage.newBuilder().setAsBool(true))
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TypeUnionDoubleMember",
            new BsonDocument()
                .append("typeUnion", new BsonDouble(3.14159))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTypeUnion(TypeUnionValueMessage.newBuilder().setAsDouble(3.14159))
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TypeUnionObjectIdMember",
            new BsonDocument()
                .append("typeUnion", new BsonObjectId(OBJECT_ID))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTypeUnion(
                    TypeUnionValueMessage.newBuilder()
                        .setAsObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID)))
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TypeUnionDocumentMember",
            new BsonDocument()
                .append(
                    "typeUnion",
                    new BsonDocument()
                        .append("type", new BsonString("fooBar"))
                        .append("enabled", BsonBoolean.valueOf(true))
                        .append("fooBarMessage", new BsonString("hoi"))
                        .append("baseProperty", new BsonInt32(0)))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTypeUnion(
                    TypeUnionValueMessage.newBuilder()
                        .setAsDocument(
                            DiscriminatedUnionMessage.newBuilder()
                                .setFooBar(
                                    DiscriminatedUnionMessage.FooBar.newBuilder()
                                        .setEnabled(true)
                                        .setFooBarMessage("hoi"))))
                .setScalarWithoutPresence(0)),
        new TestCase(
            "TypeUnionArrayMember",
            new BsonDocument()
                .append("typeUnion", new BsonArray(List.of(new BsonInt64(42), new BsonInt64(37))))
                .append("scalarWithoutPresence", new BsonInt32(0)),
            TestMessage.newBuilder()
                .setTypeUnion(
                    TypeUnionValueMessage.newBuilder()
                        .setAsArray(RepeatedValueMessage.newBuilder().addPoint(42).addPoint(37)))
                .setScalarWithoutPresence(0)),
        new TestCase(
            "Oneof",
            new BsonDocument()
                .append("fooBar", new BsonDocument("enabled", BsonBoolean.valueOf(true)))
                .append("baseProperty", new BsonInt32(0)),
            OneofMessage.newBuilder()
                .setFooBar(OneofMessage.FooBar.newBuilder().setEnabled(true).build())),
        new TestCase(
            "OneofNoneSet",
            new BsonDocument("baseProperty", new BsonInt32(10)),
            OneofMessage.newBuilder().setBaseProperty(10)),
        new TestCase(
            "OneofJsonName",
            new BsonDocument()
                .append("batFoo", new BsonDocument("enabled", BsonBoolean.valueOf(true)))
                .append("baseProperty", new BsonInt32(0)),
            OneofMessage.newBuilder()
                .setFooBat(OneofMessage.FooBat.newBuilder().setEnabled(true).build())),
        new TestCase(
            "WKValueBoolean", BsonBoolean.valueOf(true), ProtoValue.newBuilder().setBool(true)),
        new TestCase("WKValueInt32", new BsonInt32(7), ProtoValue.newBuilder().setInt32(7)),
        new TestCase("WKValueInt64", new BsonInt64(13), ProtoValue.newBuilder().setInt64(13)),
        new TestCase(
            "WKValueDouble", new BsonDouble(3.14), ProtoValue.newBuilder().setDouble(3.14)),
        new TestCase(
            "WKValueDateTime", new BsonDateTime(42), ProtoValue.newBuilder().setUtcDatetime(42)),
        new TestCase(
            "WKValueTimestamp", new BsonTimestamp(42), ProtoValue.newBuilder().setTimestamp(42)),
        new TestCase(
            "WKValueString", new BsonString("hoi"), ProtoValue.newBuilder().setString("hoi")),
        new TestCase(
            "WKValueBinary",
            new BsonBinary((byte) -1, new byte[] {0xd, 0, 0, 0xd}),
            ProtoValue.newBuilder()
                .setBinary(
                    ProtoBinaryData.newBuilder()
                        .setSubTypeValue(255)
                        .setData(ByteString.copyFrom(new byte[] {0xd, 0, 0, 0xd})))),
        new TestCase(
            "WKValueArray",
            new BsonArray(List.of(BsonBoolean.valueOf(true), new BsonInt32(7))),
            ProtoValue.newBuilder()
                .setArray(
                    ProtoArray.newBuilder()
                        .addValue(ProtoValue.newBuilder().setBool(true))
                        .addValue(ProtoValue.newBuilder().setInt32(7)))),
        new TestCase(
            "WKValueObjectId", new BsonObjectId(OBJECT_ID), ProtoValue.parseBsonFrom(OBJECT_ID)),
        new TestCase(
            "WKValueRegEx",
            new BsonRegularExpression(".*", "i"),
            ProtoValue.newBuilder()
                .setRegularExpression(
                    ProtoRegularExpression.newBuilder().setPattern(".*").setOptions("i"))),
        new TestCase(
            "WKValueJavaScript",
            new BsonJavaScript("code"),
            ProtoValue.newBuilder().setJavaScript("code")),
        new TestCase(
            "WKValueDecimal128",
            new BsonDecimal128(Decimal128.fromIEEE754BIDEncoding(13, 7)),
            ProtoValue.newBuilder()
                .setDecimal128(
                    ByteString.copyFrom(
                        new byte[] {7, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0}))),
        new TestCase(
            "WKValueDbPointer",
            new BsonDbPointer("ns", OBJECT_ID),
            ProtoValue.newBuilder()
                .setDbPointer(
                    ProtoDbPointer.newBuilder()
                        .setNamespace("ns")
                        .setObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID)))),
        new TestCase(
            "WKValueSymbol", new BsonSymbol("sym"), ProtoValue.newBuilder().setSymbol("sym")),
        new TestCase(
            "WKValueJavaScriptWithScope",
            new BsonJavaScriptWithScope(
                "code", new BsonDocument("flag", BsonBoolean.valueOf(true))),
            ProtoValue.newBuilder()
                .setJavaScriptWithScope(
                    ProtoJavaScriptWithScope.newBuilder()
                        .setCode("code")
                        .setScope(
                            ProtoDocument.newBuilder()
                                .addField("flag", ProtoValue.newBuilder().setBool(true))))),
        new TestCase("WKDateTime", new BsonDateTime(42), ProtoDateTime.newBuilder().setRep(42)),
        new TestCase("WKTimestamp", new BsonTimestamp(42), ProtoTimestamp.newBuilder().setRep(42)),
        new TestCase(
            "WKBinary",
            new BsonBinary((byte) -1, new byte[] {0xd, 0, 0, 0xd}),
            ProtoBinaryData.newBuilder()
                .setSubTypeValue(255)
                .setData(ByteString.copyFrom(new byte[] {0xd, 0, 0, 0xd}))),
        new TestCase(
            "WKArray",
            new BsonArray(List.of(BsonBoolean.valueOf(true), new BsonInt32(7))),
            ProtoArray.newBuilder()
                .addValue(ProtoValue.newBuilder().setBool(true))
                .addValue(ProtoValue.newBuilder().setInt32(7))),
        new TestCase(
            "WKObjectId", new BsonObjectId(OBJECT_ID), ProtoObjectId.parseBsonFrom(OBJECT_ID)),
        new TestCase(
            "WKRegEx",
            new BsonRegularExpression(".*", "i"),
            ProtoRegularExpression.newBuilder().setPattern(".*").setOptions("i")),
        new TestCase(
            "WKJavaScript",
            new BsonJavaScript("code"),
            ProtoJavaScript.newBuilder().setCode("code")),
        new TestCase(
            "WKDecimal128",
            new BsonDecimal128(Decimal128.fromIEEE754BIDEncoding(13, 7)),
            ProtoDecimal128.parseBsonFrom(Decimal128.fromIEEE754BIDEncoding(13, 7))),
        new TestCase(
            "WKDbPointer",
            new BsonDbPointer("ns", OBJECT_ID),
            ProtoDbPointer.newBuilder()
                .setNamespace("ns")
                .setObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID))),
        new TestCase("WKSymbol", new BsonSymbol("sym"), ProtoSymbol.newBuilder().setRep("sym")),
        new TestCase(
            "WKJavaScriptWithScope",
            new BsonJavaScriptWithScope(
                "code", new BsonDocument("flag", BsonBoolean.valueOf(true))),
            ProtoJavaScriptWithScope.newBuilder()
                .setCode("code")
                .setScope(
                    ProtoDocument.newBuilder()
                        .addField("flag", ProtoValue.newBuilder().setBool(true)))),
        new TestCase(
            "ValueArray",
            new BsonArray(List.of(new BsonInt64(7), new BsonInt64(13))),
            RepeatedValueMessage.newBuilder().addPoint(7).addPoint(13)),
        new TestCase(
            "ValueTypeUnionBoolean",
            BsonBoolean.valueOf(true),
            TypeUnionValueMessage.newBuilder().setAsBool(true)),
        new TestCase(
            "ValueTypeUnionDouble",
            new BsonDouble(3.14),
            TypeUnionValueMessage.newBuilder().setAsDouble(3.14)),
        new TestCase(
            "ValueTypeUnionObjectId",
            new BsonObjectId(OBJECT_ID),
            TypeUnionValueMessage.newBuilder()
                .setAsObjectId(ProtoObjectId.parseBsonFrom(OBJECT_ID))),
        new TestCase(
            "ValueTypeUnionDocument",
            new BsonDocument("baseProperty", new BsonInt32(1)),
            TypeUnionValueMessage.newBuilder()
                .setAsDocument(DiscriminatedUnionMessage.newBuilder().setBaseProperty(1).build())),
        new TestCase(
            "ValueTypeUnionArray",
            new BsonArray(List.of(new BsonInt64(7), new BsonInt64(13))),
            TypeUnionValueMessage.newBuilder()
                .setAsArray(RepeatedValueMessage.newBuilder().addPoint(7).addPoint(13).build())));
  }

  @RunWith(Parameterized.class)
  public static class ProtoToBson {
    @Parameterized.Parameters(name = "ProtoToBson {0}")
    public static List<TestCase> cases() {
      return common_cases();
    }

    private final TestCase testCase;

    public ProtoToBson(TestCase testCase) {
      this.testCase = testCase;
    }

    @Test
    public void test() {
      assertEquals(this.testCase.bson, this.testCase.proto.toBson());
    }
  }

  @RunWith(Parameterized.class)
  public static class BsonToProto {
    @Parameterized.Parameters(name = "BsonToProto {0}")
    public static List<TestCase> cases() {
      var extra_cases =
          List.of(
              new TestCase(
                  "TestInt32ValueFromInt64",
                  new BsonDocument("intSingle", new BsonInt64(32)),
                  TestMessage.newBuilder().setIntSingle(32)),
              new TestCase(
                  "TestInt32ValueFromDouble",
                  new BsonDocument("intSingle", new BsonDouble(32)),
                  TestMessage.newBuilder().setIntSingle(32)),
              new TestCase(
                  "TestInt64ValueFromInt32",
                  new BsonDocument("intDouble", new BsonInt32(64)),
                  TestMessage.newBuilder().setIntDouble(64)),
              new TestCase(
                  "TestInt64ValueFromDouble",
                  new BsonDocument("intDouble", new BsonDouble(64)),
                  TestMessage.newBuilder().setIntDouble(64)),
              new TestCase(
                  "TestFloatValueFromInt32",
                  new BsonDocument("floatSingle", new BsonInt32(32)),
                  TestMessage.newBuilder().setFloatSingle(32)),
              new TestCase(
                  "TestFloatValueFromInt64",
                  new BsonDocument("floatSingle", new BsonInt64(64)),
                  TestMessage.newBuilder().setFloatSingle(64)),
              new TestCase(
                  "TestFloatValueFromDouble",
                  new BsonDocument("floatSingle", new BsonDouble(64)),
                  TestMessage.newBuilder().setFloatSingle(64)),
              new TestCase(
                  "TestDoubleValueFromInt32",
                  new BsonDocument("floatDouble", new BsonInt32(32)),
                  TestMessage.newBuilder().setFloatDouble(32)),
              new TestCase(
                  "TestDoubleValueFromInt64",
                  new BsonDocument("floatDouble", new BsonInt64(64)),
                  TestMessage.newBuilder().setFloatDouble(64)),
              new TestCase(
                  "TestFieldAllowUnknownFields",
                  new BsonDocument(
                      "nestedAllowUnknown",
                      new BsonDocument()
                          .append("textString", new BsonString("Hello"))
                          .append("iAmUnknown", BsonBoolean.valueOf(true))),
                  TestMessage.newBuilder()
                      .setNestedAllowUnknown(
                          TestMessage.Nested.newBuilder().setTextString("Hello"))),
              new TestCase(
                  "DiscriminatedUnionVariantAllowsUnknown",
                  new BsonDocument()
                      .append("type", new BsonString("barBat"))
                      .append("enabled", BsonBoolean.valueOf(true))
                      .append("barBatMessage", new BsonString("foobar"))
                      .append("baseProperty", new BsonInt32(0))
                      .append("itsUnknown", BsonBoolean.valueOf(true)),
                  DiscriminatedUnionMessage.newBuilder()
                      .setBarBat(
                          DiscriminatedUnionMessage.BarBat.newBuilder()
                              .setEnabled(true)
                              .setBarBatMessage("foobar")
                              .build())),
              new TestCase(
                  "OneofAllowUnknownFields",
                  new BsonDocument()
                      .append("barBat", new BsonDocument("enabled", BsonBoolean.valueOf(true)))
                      .append("baseProperty", new BsonInt32(0)),
                  OneofMessage.newBuilder()
                      .setBarBat(OneofMessage.BarBat.newBuilder().setEnabled(true).build())),
              new TestCase(
                  "MapValueAllowUnknownFields",
                  new BsonDocument(
                      "mapUnknownFields",
                      new BsonDocument(
                          "test",
                          new BsonDocument()
                              .append("message", new BsonString("hoi"))
                              .append("ignored", BsonBoolean.valueOf(true)))),
                  TestMessage.newBuilder()
                      .putMapUnknownFields(
                          "test", Greeting.newBuilder().setMessage("hoi").build())),
              // Value key repeated for a scalar field collapsed to the last value.
              new TestCase(
                  "BinaryRepeatedToScalar",
                  RawBsonDocument.parse("{\"message\": \"hello\", \"message\": \"world\"}"),
                  Greeting.newBuilder().setMessage("world")));
      return Stream.concat(common_cases().stream(), extra_cases.stream())
          .collect(Collectors.toList());
    }

    private final TestCase testCase;

    public BsonToProto(TestCase testCase) {
      this.testCase = testCase;
    }

    @Test
    public void test() throws BsonProtoParseException {
      assertEquals(
          this.testCase.proto,
          this.testCase.proto.newBuilderForType().mergeBsonFrom(this.testCase.bson).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class BsonToProtoException {
    @Parameterized.Parameters(name = "{0}")
    public static List<TestCase> cases() {
      return List.of(
          new TestCase(
              "StringTypeMismatch",
              new BsonDocument("textString", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "BytesTypeMismatch",
              new BsonDocument("byteString", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "EnumTypeMismatch",
              new BsonDocument("topLevelEnum", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "BooleanTypeMismatch",
              new BsonDocument("boolean", new BsonInt32(1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeMismatch",
              new BsonDocument("intSingle", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeLongOverflow",
              new BsonDocument("intSingle", new BsonInt64(((long) Integer.MAX_VALUE) + 1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeLongUnderflow",
              new BsonDocument("intSingle", new BsonInt64(((long) Integer.MIN_VALUE) - 1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeDoubleOverflow",
              new BsonDocument("intSingle", new BsonDouble(((double) Integer.MAX_VALUE) + 1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeDoubleUnderflow",
              new BsonDocument("intSingle", new BsonDouble(((double) Integer.MIN_VALUE) - 1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "IntTypeDoubleFraction",
              new BsonDocument("intSingle", new BsonDouble(0.1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "LongTypeMismatch",
              new BsonDocument("intDouble", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "LongTypeDoubleOverflow",
              new BsonDocument("intDouble", new BsonDouble(((double) Long.MAX_VALUE) * 2)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "LongTypeDoubleUnderflow",
              new BsonDocument("intDouble", new BsonDouble(((double) Long.MIN_VALUE) * 2)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "LongTypeDoubleFraction",
              new BsonDocument("intDouble", new BsonDouble(0.1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "FloatTypeMismatch",
              new BsonDocument("floatSingle", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "FloatTypeDoubleOverflow",
              new BsonDocument("floatSingle", new BsonDouble(Float.MAX_VALUE * 1.1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "FloatTypeDoubleUnderflow",
              new BsonDocument("floatSingle", new BsonDouble(Float.MAX_VALUE * -1.1)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "DoubleTypeMismatch",
              new BsonDocument("floatDouble", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "MessageTypeMismatch",
              new BsonDocument("nestedObject", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "ScalarToRepeatedMismatch",
              new BsonDocument("floatDoubles", new BsonDouble(0.42)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "RepeatedToScalarMismatch",
              new BsonDocument("floatDouble", new BsonArray(List.of(new BsonDouble(0.42)))),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "MapTypeMismatch",
              new BsonDocument("mapStringToInt", BsonBoolean.valueOf(true)),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "MapValueTypeMismatch",
              new BsonDocument(
                  "mapStringToInt",
                  new BsonDocument()
                      .append("foo", new BsonInt32(1))
                      .append("bar", BsonBoolean.valueOf(true))),
              TestMessage.getDefaultInstance()),
          new TestCase(
              "ArrayTypeMismatch",
              new BsonDocument("array", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "DateTimeTypeMismatch",
              new BsonDocument("dateTime", new BsonInt64(2)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "TimestampTypeMismatch",
              new BsonDocument("timestamp", new BsonInt64(2)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "BinaryTypeMismatch",
              new BsonDocument("binary", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "ObjectIdTypeMismatch",
              new BsonDocument("objectId", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "Decimal128TypeMismatch",
              new BsonDocument("decimal128", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "RegularExpressionTypeMismatch",
              new BsonDocument("regex", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "DbPointerTypeMismatch",
              new BsonDocument("dbPointer", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "SymbolTypeMismatch",
              new BsonDocument("symbol", new BsonString("sym")),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "JavaScriptTypeMismatch",
              new BsonDocument("javaScript", new BsonString("code")),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "JSWithScopeTypeMismatch",
              new BsonDocument("javaScriptWithScope", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "WKTDocTypeMismatch",
              new BsonDocument("doc", BsonBoolean.valueOf(true)),
              WKTMessage.getDefaultInstance()),
          new TestCase(
              "RepeatedValueMessageTypeMismatch",
              new BsonArray(List.of(BsonBoolean.valueOf(true))),
              TypeUnionValueMessage.getDefaultInstance()),
          new TestCase(
              "TypeUnionTypeMismatch",
              new BsonInt32(7),
              TypeUnionValueMessage.getDefaultInstance()),
          new TestCase(
              "DiscriminatedUnionNoVariantUnknownFields",
              new BsonDocument()
                  .append("type", new BsonString("fooBar"))
                  .append("isUnknown", BsonBoolean.valueOf(true)),
              DiscriminatedUnionMessage.getDefaultInstance()),
          new TestCase(
              "DiscriminatedUnionUnknownVariant",
              new BsonDocument().append("type", new BsonString("isUnknown")),
              DiscriminatedUnionMessage.getDefaultInstance()));
    }

    private final TestCase testCase;

    public BsonToProtoException(TestCase testCase) {
      this.testCase = testCase;
    }

    @Test
    public void test() {
      Assert.assertThrows(
          BsonProtoParseException.class,
          () -> this.testCase.proto.newBuilderForType().mergeBsonFrom(this.testCase.bson));
    }
  }

  public static class ExtraTests {
    @Test
    public void testAllowTopLevelUnknownFields() throws BsonProtoParseException {
      assertEquals(
          TestMessage.newBuilder()
              .setNestedAllowUnknown(TestMessage.Nested.newBuilder().setTextString("Hello"))
              .build(),
          TestMessage.newBuilder()
              .mergeBsonFrom(
                  new BsonDocumentReader(
                      new BsonDocument(
                          "nestedAllowUnknown",
                          new BsonDocument()
                              .append("textString", new BsonString("Hello"))
                              .append("iAmUnknown", BsonBoolean.valueOf(true)))),
                  true)
              .build());
    }

    @Test
    public void testDiscriminatedUnionNoneSetAllowUnknownFields() throws BsonProtoParseException {
      assertEquals(
          DiscriminatedUnionMessage.newBuilder().setBaseProperty(10).build(),
          DiscriminatedUnionMessage.newBuilder()
              .mergeBsonFrom(
                  new BsonDocumentReader(
                      new BsonDocument()
                          .append("baseProperty", new BsonInt32(10))
                          .append("isUnknown", BsonBoolean.valueOf(true))),
                  true)
              .build());
    }

    @Test
    public void testDiscriminatedUnknownVariantAllowUnknownFields() throws BsonProtoParseException {
      assertEquals(
          DiscriminatedUnionMessage.newBuilder().setBaseProperty(10).build(),
          DiscriminatedUnionMessage.newBuilder()
              .mergeBsonFrom(
                  new BsonDocumentReader(
                      new BsonDocument()
                          .append("baseProperty", new BsonInt32(10))
                          .append("type", new BsonString("barQuux"))
                          .append("isUnknown", BsonBoolean.valueOf(true))),
                  true)
              .build());
    }
  }
}
