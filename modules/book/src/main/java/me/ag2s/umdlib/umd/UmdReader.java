package me.ag2s.umdlib.umd;


import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

import me.ag2s.umdlib.domain.UmdBook;
import me.ag2s.umdlib.domain.UmdCover;
import me.ag2s.umdlib.domain.UmdHeader;
import me.ag2s.umdlib.tool.StreamReader;
import me.ag2s.umdlib.tool.UmdUtils;

/**
 * UMD格式的电子书解析
 * 格式规范参考：
 * http://blog.sina.com.cn/s/blog_7c8dc2d501018o5d.html
 * http://blog.sina.com.cn/s/blog_7c8dc2d501018o5l.html
 */
public class UmdReader {
    private final UmdReaderLimits limits;
    UmdBook book;
    int _AdditionalCheckNumber;
    int _TotalContentLen = -1;
    boolean end = false;
    boolean chapterOffsetsRead;
    boolean chapterTitlesRead;

    public UmdReader() {
        this(UmdReaderLimits.defaults());
    }

    public UmdReader(UmdReaderLimits limits) {
        if (limits == null) {
            throw new NullPointerException("limits");
        }
        this.limits = limits;
    }

    public synchronized UmdBook read(InputStream inputStream) throws Exception {

        book = new UmdBook();
        _AdditionalCheckNumber = 0;
        _TotalContentLen = -1;
        end = false;
        chapterOffsetsRead = false;
        chapterTitlesRead = false;
        StreamReader reader = new StreamReader(inputStream);
        UmdHeader umdHeader = new UmdHeader();
        book.setHeader(umdHeader);
        if (reader.readIntLe() != 0xde9a9b89) {
            throw new IOException("Wrong header");
        }
        short num1 = -1;
        byte ch = reader.readByte();
        while (ch == 35) {
            //int num2=reader.readByte();
            short segType = reader.readShortLe();
            byte segFlag = reader.readByte();
            short len = (short) (reader.readUint8() - 5);
            if (len < 0) {
                throw new IOException("Invalid UMD section length: " + (len + 5));
            }

            System.out.println("块标识:" + segType);
            //short length1 = reader.readByte();
            readSection(segType, segFlag, len, reader, umdHeader);
            if (end) {
                break;
            }

            if ((int) segType == 241 || (int) segType == 10) {
                segType = num1;
            }
            for (ch = reader.readByte(); ch == 36; ch = reader.readByte()) {
                //int num3 = reader.readByte();
                System.out.println(ch);
                int additionalCheckNumber = reader.readIntLe();
                int rawLength = reader.readIntLe();
                if (rawLength < 9 || rawLength > (long) limits.getMaxAdditionalPayloadBytes() + 9L) {
                    throw new IOException("Invalid UMD additional section length: " + rawLength);
                }
                int length2 = rawLength - 9;
                readAdditionalSection(segType, additionalCheckNumber, length2, reader);
            }
            num1 = segType;

        }
        if (!end) {
            throw new IOException("UMD end section is missing");
        }
        validateContentLength();
        System.out.println(book.getHeader().toString());
        return book;

    }

    private void readAdditionalSection(short segType, int additionalCheckNumber, int length, StreamReader reader) throws Exception {
        switch (segType) {
            case 14:
                //this._TotalImageList.Add((object) Image.FromStream((Stream) new MemoryStream(reader.ReadBytes((int) length))));
                reader.skip(length);
                break;
            case 15:
                //this._TotalImageList.Add((object) Image.FromStream((Stream) new MemoryStream(reader.ReadBytes((int) length))));
                reader.skip(length);
                break;
            case 129:
                reader.skip(length);
                break;
            case 130:
                //byte[] covers = reader.readBytes(length);
                book.setCover(new UmdCover(reader.readBytes(length)));
                //this._Book.Cover = BitmapImage.FromStream((Stream) new MemoryStream(reader.ReadBytes((int) length)));
                break;
            case 131:
                if (chapterOffsetsRead) {
                    throw new IOException("Duplicate UMD chapter offset section");
                }
                if ((length & 3) != 0) {
                    throw new IOException("Invalid UMD chapter offset payload length: " + length);
                }
                int chapterCount = length / 4;
                if (chapterCount > limits.getMaxChapterCount()) {
                    throw new IOException("UMD chapter count exceeds limit: " + chapterCount);
                }
                if (_TotalContentLen < 0) {
                    throw new IOException("UMD chapter offsets appeared before content length");
                }
                chapterOffsetsRead = true;
                System.out.println(chapterCount);
                book.setNum(chapterCount);
                int previousOffset = -1;
                for (int i = 0; i < chapterCount; ++i) {
                    int offset = reader.readIntLe();
                    if ((i == 0 && offset != 0)
                            || offset < previousOffset
                            || offset > _TotalContentLen) {
                        throw new IOException("Invalid UMD chapter offset at index " + i + ": " + offset);
                    }
                    book.getChapters().addContentLength(offset);
                    previousOffset = offset;
                }
                break;
            case 132:
                //System.out.println(length/4);
                System.out.println(_AdditionalCheckNumber);
                System.out.println(additionalCheckNumber);
                if (this._AdditionalCheckNumber != additionalCheckNumber) {
                    if (_TotalContentLen < 0) {
                        throw new IOException("UMD content appeared before its declared length");
                    }
                    int remaining = _TotalContentLen - book.getChapters().contents.size();
                    if (remaining < 0) {
                        throw new IOException("UMD content exceeds its declared length");
                    }
                    if (length == 0) {
                        throw new IOException("UMD content block is empty");
                    }
                    int blockLimit = Math.min(remaining, limits.getMaxDecompressedBlockBytes());
                    byte[] decompressed = UmdUtils.decompress(reader.readBytes(length), blockLimit);
                    book.getChapters().contents.write(decompressed);
                    book.getChapters().contents.flush();
                    break;
                } else {
                    if (chapterTitlesRead) {
                        throw new IOException("Duplicate UMD chapter title section");
                    }
                    if (!chapterOffsetsRead || book.getNum() > limits.getMaxChapterCount()) {
                        throw new IOException("UMD chapter titles appeared before valid offsets");
                    }
                    chapterTitlesRead = true;
                    int consumed = 0;
                    int titleBytes = 0;
                    for (int i = 0; i < book.getNum(); i++) {
                        if (consumed >= length) {
                            throw new IOException("Truncated UMD chapter title payload");
                        }
                        short len = reader.readUint8();
                        consumed++;
                        if (len > length - consumed) {
                            throw new IOException("UMD chapter title exceeds its payload boundary");
                        }
                        if (len > limits.getMaxTitleBytes() - titleBytes) {
                            throw new IOException("UMD chapter titles exceed configured limit");
                        }
                        byte[] title = reader.readBytes(len);
                        consumed += len;
                        titleBytes += len;
                        //System.out.println(UmdUtils.unicodeBytesToString(title));
                        book.getChapters().addTitle(title);
                    }
                    if (consumed != length) {
                        throw new IOException("Unexpected trailing bytes in UMD chapter title payload");
                    }
                }


                break;
            default:
                    /*Console.WriteLine("未知内容");
                    Console.WriteLine("Seg Type = " + (object) segType);
                    Console.WriteLine("Seg Len = " + (object) length);
                    Console.WriteLine("content = " + (object) reader.ReadBytes((int) length));*/
                reader.skip(length);
                break;
        }
    }

    public void readSection(short segType, byte segFlag, short length, StreamReader reader, UmdHeader header) throws IOException {
        switch (segType) {
            case 1://umd文件头 DCTS_CMD_ID_VERSION
                requireSectionLength(segType, length, 3);
                header.setUmdType(reader.readByte());
                reader.readBytes(2);//Random 2
                System.out.println("UMD文件类型:" + header.getUmdType());
                break;
            case 2://文件标题 DCTS_CMD_ID_TITLE
                header.setTitle(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("文件标题:" + header.getTitle());
                break;
            case 3://作者
                header.setAuthor(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("作者:" + header.getAuthor());
                break;
            case 4://年
                header.setYear(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("年:" + header.getYear());
                break;
            case 5://月
                header.setMonth(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("月:" + header.getMonth());
                break;
            case 6://日
                header.setDay(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("日:" + header.getDay());
                break;
            case 7://小说类型
                header.setBookType(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("小说类型:" + header.getBookType());
                break;
            case 8://出版商
                header.setBookMan(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("出版商:" + header.getBookMan());
                break;
            case 9:// 零售商
                header.setShopKeeper(UmdUtils.unicodeBytesToString(reader.readBytes(length)));
                System.out.println("零售商:" + header.getShopKeeper());
                break;
            case 10://CONTENT ID
                System.out.println("CONTENT ID:" + reader.readHex(length));
                break;
            case 11:
                //内容长度 DCTS_CMD_ID_FILE_LENGTH
                requireSectionLength(segType, length, 4);
                int declaredContentLen = reader.readIntLe();
                if (declaredContentLen < 0 || declaredContentLen > limits.getMaxContentBytes()) {
                    throw new IOException(
                            "UMD content length " + declaredContentLen
                                    + " exceeds limit " + limits.getMaxContentBytes()
                    );
                }
                if (_TotalContentLen >= 0 && _TotalContentLen != declaredContentLen) {
                    throw new IOException("Conflicting UMD content length declarations");
                }
                _TotalContentLen = declaredContentLen;
                book.getChapters().setTotalContentLen(_TotalContentLen);
                System.out.println("内容长度:" + _TotalContentLen);
                break;
            case 12://UMD文件结束
                requireSectionLength(segType, length, 4);
                int num2 = reader.readIntLe();
                end = true;
                System.out.println("整个文件长度" + num2);
                break;
            case 13:
                reader.skip(length);
                break;
            case 14:
                requireSectionLength(segType, length, 1);
                int num3 = reader.readByte();
                break;
            case 15:
                reader.readBytes(length);
                break;
            case 129://正文
            case 131://章节偏移
                requireSectionLength(segType, length, 4);
                _AdditionalCheckNumber = reader.readIntLe();
                System.out.println("章节偏移:" + _AdditionalCheckNumber);
                break;
            case 132://章节标题，正文
                requireSectionLength(segType, length, 4);
                _AdditionalCheckNumber = reader.readIntLe();
                System.out.println("章节标题，正文:" + _AdditionalCheckNumber);
                break;
            case 130://封面（jpg）
                requireSectionLength(segType, length, 5);
                int num4 = reader.readByte();
                _AdditionalCheckNumber = reader.readIntLe();
                break;
            case 135://页面偏移（Page Offset）
                requireSectionLength(segType, length, 6);
                reader.readUint8();//fontSize 一字节 字体大小
                reader.readUint8();//screenWidth 屏幕宽度
                reader.readBytes(4);//BlockRandom 指向一个页面偏移数据块
                break;
            case 240://CDS KEY
                reader.skip(length);
                break;
            case 241://许可证(LICENCE KEY)
                requireSectionLength(segType, length, 16);
                //System.out.println("整个文件长度" + length);
                System.out.println("许可证(LICENCE KEY):" + reader.readHex(16));
                break;
            default:
                if (length > 0) {
                    byte[] numArray = reader.readBytes(length);
                }


        }
    }

    private void validateContentLength() throws IOException {
        if (_TotalContentLen >= 0 && book.getChapters().contents.size() != _TotalContentLen) {
            throw new IOException(
                    "UMD content length mismatch: declared " + _TotalContentLen
                            + ", actual " + book.getChapters().contents.size()
            );
        }
    }

    private void requireSectionLength(short sectionType, short actual, int expected) throws IOException {
        if (actual != expected) {
            throw new IOException(
                    "Invalid UMD section " + sectionType + " length: " + actual
                            + ", expected " + expected
            );
        }
    }


    @Override
    @NonNull
    public String toString() {
        return "UmdReader{" +
                "book=" + book +
                '}';
    }
}
