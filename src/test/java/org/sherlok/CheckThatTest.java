package org.sherlok;

import static org.sherlok.utils.CheckThat.checkValidId;

import org.junit.Test;
import org.sherlok.utils.CheckThat;
import org.sherlok.utils.ValidationException;

public class CheckThatTest {

    @Test(expected = ValidationException.class)
    public void testStar() throws Exception {
        CheckThat.checkOnlyAlphanumDot("*_");
    }

    @Test(expected = ValidationException.class)
    public void testParenthesis() throws Exception {
        CheckThat.checkOnlyAlphanumDot("(asd)");
    }

    @Test
    public void test() throws Exception {
        CheckThat.checkOnlyAlphanumDot("abAC09.32no__in23");
    }

    @Test
    public void testValidId() throws Exception {
        checkValidId("ab:cd");
    }

    @Test
    public void testValidIdWithUnderscores() throws Exception {
        checkValidId("a_b:c_d");
    }

    @Test(expected = ValidationException.class)
    public void testValidIdTwoColumns() throws Exception {
        checkValidId("a:_b:c_d");
    }

    @Test(expected = ValidationException.class)
    public void testValidIdMissingColumn() throws Exception {
        checkValidId("a_bc_d");
    }
}
