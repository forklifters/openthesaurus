/**
 * vthesaurus - web-based thesaurus management tool
 * Copyright (C) 2009 vionto GmbH, www.vionto.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */ 

import com.vionto.vithesaurus.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.text.SimpleDateFormat

class ImportController extends BaseController {
    
   //
   // FIXME: admin only
   //
   // def beforeInterceptor = [action: this.&adminAuth]
 
   // TODO:
   // -antonyme
   // -oberbegriffe
   // -lookup-version des terms (nur wenn anders als term?)?

    private final String SUPER_NAME = "more generic synset"
    private final String SUPER_NAME_REVERSE = "more specific synset"
    private final String SUPER_VERB = "is a sub concept of"

    def index = {
        []
    }
    
    def run = {
        String dburl = params.dbUrl
        String dbuser = params.dbUsername
        String dbpassword = params.dbPassword
        Class.forName(params.dbClass)
        Connection conn = DriverManager.getConnection(dburl, dbuser, dbpassword)

        cleanup(Category.findAll())
        cleanup(CategoryLink.findAll())
        new Category("other").save()
        cleanup(TermLevel.findAll())
        cleanup(ThesaurusUser.findAll())
        
        cleanup(Term.findAll())
        cleanup(Synset.findAll())
        
        importUsers(conn)
        importLinkTypes()
        Map oldSubjectIdToCategory = importCategories(conn)
        Map oldUseIdToTermLevel = importLevels(conn)

        //testing:
        //return

        String sql = "SET NAMES 'utf8'"
        render "$sql<br>"
        PreparedStatement ps = conn.prepareStatement(sql)
        ps.execute()

        //
        // import terms and synsets
        //
        sql = "SELECT id, subject_id, super_id FROM meanings WHERE hidden = 0"
        ps = conn.prepareStatement(sql)
        ResultSet rs = ps.executeQuery()
        int count = 0
        
        Language german = Language.findByShortForm("de")
        assert(german)
        Category otherCategory = Category.findByCategoryName("other")
        assert(otherCategory)
        Section otherSection = Section.findBySectionName("other")
        assert(otherSection)
        
        int savedCount = 0
        while (rs.next()) {
          sql = "SELECT word, use_id FROM words, word_meanings WHERE meaning_id = ? AND words.id = word_meanings.word_id"
          PreparedStatement ps2 = conn.prepareStatement(sql)
          int oldSynsetId = rs.getInt("id")
          ps2.setInt(1, oldSynsetId)
          // TODO: another query for uses...
          ResultSet rs2 = ps2.executeQuery()
          Synset synset = new Synset()
          synset.originalId = oldSynsetId
          CategoryLink categoryLink
          if (rs.getInt("subject_id")) {
            Category cat = oldSubjectIdToCategory.get(rs.getInt("subject_id"))
            categoryLink = new CategoryLink(synset, cat)
            synset.preferredCategory = cat
          } else {
            categoryLink = new CategoryLink(synset, otherCategory)
            synset.preferredCategory = otherCategory
          }
          
          /* FIXME: in a second run, add super links:
          if (rs.getInt("super_id")) {
            SynsetLink link = new SynsetLink(from, to, superLinkType)
            synset.addSynsetLinks()
          }
          */
          
          synset.addCategoryLink(categoryLink)
          synset.section = otherSection
          int termCount = 0
          while (rs2.next()) {
            //FIXME: how to keep existing synset id?
            String term = convert(rs2.getString("word"))
            Term t = new Term(term, german, synset)
            if (rs2.getInt("use_id")) {
              TermLevel termLevel = oldUseIdToTermLevel.get(rs2.getInt("use_id"))
              assert(termLevel)
              t.level = termLevel
            }
            synset.addToTerms(t)
            termCount++
          }
          synset.isVisible = 1
          boolean saved = synset.save()
          if (!saved) {
            // TODO: throw exception instead
            render "NOT SAVED! $synset: ${synset.errors}<br>"
            log.warn("NOT SAVED: $synset")
          } else {
            render "saved: $synset"
            savedCount++
          }
          render "<br>\n"
          count++
          //FIXME
          if (count > 3000) {
            break
          }
        }
        conn.close()
        render "- done ($savedCount) -"
    }
   
    private importUsers(Connection conn) {
      String sql = "SELECT username, password, visiblename, perms, subs_date, last_login, blocked FROM auth_user"
      PreparedStatement ps = conn.prepareStatement(sql)
      ResultSet rs = ps.executeQuery()
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      while (rs.next()) {
          //render rs.getString("username") +"/" +  rs.getString("password") +  "<br>\n"
          def perms = rs.getString("perms") == "admin" ? ThesaurusUser.ADMIN_PERM : ThesaurusUser.USER_PERM
          String password = UserController.md5sum(rs.getString("password"))
          ThesaurusUser user = new ThesaurusUser(rs.getString("username"), 
              password, perms)
          try {
            user.creationDate = rs.getDate("subs_date")
            user.lastLoginDate = rs.getDate("last_login")
          } catch (SQLException e) {
            render "Ignoring exception: $e when parsing dates for user " + rs.getString("username") + "<br>"
          }
          user.realName = rs.getString("visiblename")
          user.blocked = rs.getInt("blocked") == 1 ? true : false
          boolean saved = user.save()
          if (!saved) {
            throw new Exception("Could not save user: $user - $user.errors")
          }
      }
    }

    private void importLinkTypes() {
      LinkType superLinkType = LinkType.findByLinkName(SUPER_NAME)
      if (!superLinkType) {
        superLinkType = new LinkType(linkName: SUPER_NAME,
            otherDirectionLinkName: SUPER_NAME_REVERSE, verbName: SUPER_VERB)
        render "Creating link type: $superLinkType"
        boolean saved = superLinkType.save()
        if (!saved) {
          throw new Exception("Could not create link type: $superLinkType")
        }
      }
      assert(superLinkType)
    }

    private Map importCategories(Connection conn) {
      String sql = "SELECT id, subject FROM subjects"
      PreparedStatement ps = conn.prepareStatement(sql)
      ResultSet rs = ps.executeQuery()
      Map oldSubjectIdToCategory = new HashMap()
      while (rs.next()) {
        String catName = convert(rs.getString("subject"))
        // no idea why it's needed, but we get "Data too long for column 'category_name'" otherwise...:
        Category cat = new Category(catName)
        render "Adding category $cat<br>"
        oldSubjectIdToCategory.put(rs.getInt("id"), cat)
        boolean saved = cat.save()
        if (!saved) {
          throw new Exception("Could not save category: $cat - $cat.errors")
        }
      }
      return oldSubjectIdToCategory
    }

    private Map importLevels(Connection conn) {
      String sql = "SELECT id, name, shortname FROM uses"
      PreparedStatement ps = conn.prepareStatement(sql)
      ResultSet rs = ps.executeQuery()
      Map oldUseIdToTermLevel = new HashMap()
      while (rs.next()) {
        String useName = convert(rs.getString("name"))
        String shortUseName = convert(rs.getString("shortname"))	//FIXME: use this!
        TermLevel termLevel = new TermLevel(levelName: useName)
        render "Adding TermLevel $termLevel<br>"
        oldUseIdToTermLevel.put(rs.getInt("id"), termLevel)
        boolean saved = termLevel.save()
        if (!saved) {
          throw new Exception("Could not save termLevel: $termLevel - $termLevel.errors")
        }
      }
      return oldUseIdToTermLevel
    }

    /**
     * Fixes the broken MySQL encoding.
     */
    private String convert(String s) {
      // oh my, the encoding returned by MySQL is totally broken (also
      // depends on the version of the MySQL driver used):
      //FIXME
      //s = new String(s.getBytes("latin1"), "utf-8")
      s = s.replaceAll("Ã¤", "ä").replaceAll("Ã–", "Ö").replaceAll("Ã¼", "ü")
        .replaceAll("Ã¶", "ö")
        .replaceAll("Ãœ", "Ü")
        .replaceAll("Ã„", "Ä")
        .replaceAll("ÃŸ", "ß")
        .replaceAll("Ã©", "é")
        .replaceAll("Ãª", "ê")
        .replaceAll("Ã¨", "è")
        
      Pattern p = Pattern.compile("[:,°%#}~\\+!/\\?@\\*\\[\\]\"\$&\\.\\(\\)=;êéèàa-zA-Z0-9öäüÖÄÜß -]*")
      Matcher m = p.matcher(s)
      if (!m.matches()) {
        //throw new Exception("NO MATCH: '$s'")
        render "NO MATCH: '$s'<br>"
      }
      return s
    }
    
 
    private void cleanup(List l) {
      int i = 0
      for (item in l) {
        //render "delete $item<br>"
        if (i == l.size() - 1) {
          render "flushing delete at $i<br>"
          item.delete(flush:true)
        } else {
          item.delete()
        }
        i++
      }
    }

}