/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.auth.page.login;

import static org.keycloak.testsuite.util.WaitUtils.waitUntilElement;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

/**
 *
 * @author <a href="mailto:vramik@redhat.com">Vlastislav Ramik</a>
 */
public class VerifyEmail extends Authenticate {

    @FindBy(xpath = "//div[@id='kc-form-wrapper']/p")
    private WebElement instruction;

    @FindBy(id = "kc-error-message")
    private WebElement error;

    public String getInstructionMessage() {
        waitUntilElement(instruction).is().present();
        return instruction.getText();
    }

    public String getErrorMessage() {
        waitUntilElement(error).is().present();
        return error.getText();
    }
}
