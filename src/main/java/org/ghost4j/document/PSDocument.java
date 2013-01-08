/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.xmlgraphics.ps.DSCConstants;
import org.apache.xmlgraphics.ps.PSGenerator;
import org.apache.xmlgraphics.ps.dsc.DSCException;
import org.apache.xmlgraphics.ps.dsc.DSCFilter;
import org.apache.xmlgraphics.ps.dsc.DSCParser;
import org.apache.xmlgraphics.ps.dsc.DefaultNestedDocumentHandler;
import org.apache.xmlgraphics.ps.dsc.events.DSCAtend;
import org.apache.xmlgraphics.ps.dsc.events.DSCComment;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentEndOfFile;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPage;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPages;
import org.apache.xmlgraphics.ps.dsc.events.DSCEvent;
import org.apache.xmlgraphics.ps.dsc.events.DSCHeaderComment;
import org.apache.xmlgraphics.ps.dsc.tools.DSCTools;
import org.apache.xmlgraphics.ps.dsc.tools.PageExtractor;

/**
 * Class representing a PostScript document.
 * 
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class PSDocument extends AbstractDocument {

	/**
	 * Serial version UID.
	 */
	private static final long serialVersionUID = 7225098893496658222L;

	public void load(InputStream inputStream) throws IOException {

		super.load(inputStream);

		// check that the file is a PostScript
		ByteArrayInputStream bais = null;
		try {

			bais = new ByteArrayInputStream(content);

			DSCParser parser = new DSCParser(bais);
			if (parser.nextDSCComment(DSCConstants.END_COMMENTS) == null) {
				throw new IOException("PostScript document is not valid");
			}

		} catch (DSCException e) {
			throw new IOException(e.getMessage());
		} finally {
			IOUtils.closeQuietly(bais);
		}
	}

	public int getPageCount() throws DocumentException {

		int pageCount = 0;

		if (content == null) {
			return pageCount;
		}

		ByteArrayInputStream bais = null;

		try {

			// read pages from the %%Pages DSC comment

			bais = new ByteArrayInputStream(content);

			DSCParser parser = new DSCParser(bais);
			Object tP = parser.nextDSCComment(DSCConstants.PAGES);
			while (tP instanceof DSCAtend)
				tP = parser.nextDSCComment(DSCConstants.PAGES);
			DSCCommentPages pages = (DSCCommentPages) tP;
			pageCount = pages.getPageCount();

		} catch (Exception e) {
			throw new DocumentException(e);
		} finally {
			IOUtils.closeQuietly(bais);
		}

		return pageCount;
	}

	public Document extractPages(int begin, int end) throws DocumentException {

		this.assertValidPageRange(begin, end);

		PSDocument result = new PSDocument();

		ByteArrayInputStream bais = null;
		ByteArrayOutputStream baos = null;

		if (content != null) {

			try {

				bais = new ByteArrayInputStream(content);
				baos = new ByteArrayOutputStream();

				PageExtractor.extractPages(bais, baos, begin, end);

				result.load(new ByteArrayInputStream(baos.toByteArray()));

			} catch (Exception e) {
				throw new DocumentException(e);
			} finally {
				IOUtils.closeQuietly(bais);
				IOUtils.closeQuietly(baos);
			}

		}

		return result;
	}

	public void appendPages(Document document) throws DocumentException {

		super.appendPages(document);

		ByteArrayInputStream baisCurrent = null;
		ByteArrayInputStream baisNew = null;
		ByteArrayOutputStream baos = null;

		int currentPageCount = this.getPageCount();
		int totalPageCount = currentPageCount + document.getPageCount();

		try {
			baisCurrent = new ByteArrayInputStream(content);
			baos = new ByteArrayOutputStream();

			DSCParser currentParser = new DSCParser(baisCurrent);
			PSGenerator gen = new PSGenerator(baos);
			currentParser.addListener(new DefaultNestedDocumentHandler(gen));

			// skip DSC header
			DSCHeaderComment header = DSCTools.checkAndSkipDSC30Header(currentParser);
			header.generate(gen);
			// set number of pages
			DSCCommentPages pages = new DSCCommentPages(totalPageCount);
			pages.generate(gen);

			currentParser.setFilter(new DSCFilter() {
				public boolean accept(DSCEvent event) {
					if (event.isDSCComment()) {

						// filter %%Pages which we add manually above
						return !event.asDSCComment().getName()
								.equals(DSCConstants.PAGES);
					} else {
						return true;
					}
				}
			});

			
			//skip the prolog and to the first page
			DSCComment pageOrTrailer = currentParser.nextDSCComment(DSCConstants.PAGE,
					gen);
			if (pageOrTrailer == null) {
				throw new DSCException("Page expected, but none found");
			}

			//remove filter
			currentParser.setFilter(null);

			//process individual pages
			while (true) {
				DSCCommentPage page = (DSCCommentPage) pageOrTrailer;
				page.setPagePosition(page.getPagePosition());
				page.generate(gen);
				pageOrTrailer = DSCTools.nextPageOrTrailer(currentParser, gen);
				if (pageOrTrailer == null) {
					throw new DSCException(
							"File is not DSC-compliant: Unexpected end of file");
				} else if (!DSCConstants.PAGE.equals(pageOrTrailer.getName())) {
					break;
				}
			}
			
			//append pages of the new document now
			baisNew = new ByteArrayInputStream(document.getContent());
			DSCParser newParser = new DSCParser(baisNew);
			header = DSCTools.checkAndSkipDSC30Header(newParser);
			pageOrTrailer = newParser.nextDSCComment(DSCConstants.PAGE);
			if (pageOrTrailer == null) {
				throw new DSCException("Page expected, but none found");
			}
			int i = 1;
			while (true) {
				DSCCommentPage page = (DSCCommentPage) pageOrTrailer;
				page.setPageName(String.valueOf(currentPageCount + i));
				page.setPagePosition(currentPageCount + i);
				page.generate(gen);
				pageOrTrailer = DSCTools.nextPageOrTrailer(newParser, gen);
				if (pageOrTrailer == null) {
					throw new DSCException(
							"File is not DSC-compliant: Unexpected end of file");
				} else if (!DSCConstants.PAGE.equals(pageOrTrailer.getName())) {
					pageOrTrailer.generate(gen);
					break;
				}
				i++;
			}

			//write the rest (end)
			currentParser.setFilter(new DSCFilter() {
				public boolean accept(DSCEvent event) {
					if (event.isDSCComment()) {

						// filter %%Pages (in case of attend)
						return !event.asDSCComment().getName()
								.equals(DSCConstants.PAGES);
					} else {
						return true;
					}
				}
			});
			while (currentParser.hasNext()) {
				DSCEvent event = currentParser.nextEvent();
				event.generate(gen);	
			}

			//update current document content
			content = baos.toByteArray();

		} catch (Exception e) {
			throw new DocumentException(e);
		} finally {
			IOUtils.closeQuietly(baisCurrent);
			IOUtils.closeQuietly(baisNew);
			IOUtils.closeQuietly(baos);
		}
	}

	public String getType() {
		return "PostScript";
	}

}