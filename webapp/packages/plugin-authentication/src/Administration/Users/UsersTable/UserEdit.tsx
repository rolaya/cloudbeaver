/*
 * cloudbeaver - Cloud Database Manager
 * Copyright (C) 2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { observer } from 'mobx-react';
import { useContext, useCallback } from 'react';
import styled, { css, use } from 'reshadow';

import {
  TabsState, TabList, Tab,
  TabTitle, Loader, SubmittingForm, TabPanel,
  ErrorMessage, Button, TableItemContext,
  TableContext,
  InputField,
  Checkbox,
  useFocus,
  InputGroup
} from '@cloudbeaver/core-blocks';
import { useService, useController } from '@cloudbeaver/core-di';
import { useTranslate } from '@cloudbeaver/core-localization';
import { useStyles, composes } from '@cloudbeaver/core-theming';

import { UsersResource } from '../../UsersResource';
import { UserEditController } from './UserEditController';

const styles = composes(
  css`
    Tab {
      composes: theme-ripple theme-background-secondary theme-text-on-secondary from global;
    }

    TabList {
      composes: theme-border-color-background from global;
    }

    ErrorMessage {
      composes: theme-background-secondary from global;
    }

    SubmittingForm {
      composes: theme-background-secondary from global;
    }
  `,
  css`
    custom-connection {
      display: flex;
      flex-direction: column;
      box-sizing: border-box;
    }

    CommonDialogWrapper {
      max-height: 500px;
      min-height: 500px;
    }

    SubmittingForm, BaseTabPanel {
      flex: 1;
      display: flex;
      flex-direction: column;
    }

    TabList {
      align-items: center;
      box-sizing: border-box;
      display: inline-flex;
      width: 100%;
      padding-left: 24px;
      outline: none;
      border-bottom: solid 1px;
    }

    Tab {
      composes: theme-typography--body2 from global;
      text-transform: uppercase;
      font-weight: normal;

      &:global([aria-selected=true]) {
        font-weight: normal !important;
      }
    }

    ErrorMessage {
      position: sticky;
      bottom: 0;
      padding: 8px 24px;
    }

    fill {
      flex: 1;
    }

    connection-form[|new] {
      border-left: solid 3px;
    }

    SubmittingForm, Loader {
      min-height: 320px;
      max-height: 500px;
    }

    IconButton {
      height: 32px;
      width: 32px;
      margin-right: 16px;
    }

    Button:not(:first-child) {
      margin-right: 24px;
    }

    layout-grid {
      flex: 1;
      width: 100%;
    }
  `
);

type Props = {
  item: string;
}

export const UserEdit = observer(function UserEdit({
  item,
}: Props) {
  const tableContext = useContext(TableContext);
  const context = useContext(TableItemContext);

  const translate = useTranslate();
  const controller = useController(UserEditController, item);
  const usersResource = useService(UsersResource);
  const [focusedRef] = useFocus({ focusFirstChild: true });

  const handleCancel = useCallback(() => {
    tableContext?.setItemExpand(context?.item, false);
    if (controller.isNew) {
      usersResource.delete(item);
    }
  }, [tableContext, context]);

  const handleLoginChange = useCallback(
    (value: string) => controller.credentials.login = value,
    []
  );
  const handlePasswordChange = useCallback(
    (value: string) => controller.credentials.password = value,
    []
  );
  const handlePasswordRepeatChange = useCallback(
    (value: string) => controller.credentials.passwordRepeat = value,
    []
  );
  const handleRoleChange = useCallback(
    (roleId: string, value: boolean) => controller.credentials.roles.set(roleId, value),
    []
  );

  return styled(useStyles(styles))(
    <TabsState selectedId='info'>
      <TabList>
        <Tab tabId='info' >
          <TabTitle>{translate('authentication_administration_user_info')}</TabTitle>
        </Tab>
        <Tab tabId='connections_access' >
          <TabTitle>{translate('authentication_administration_user_connections_access')}</TabTitle>
        </Tab>
        <fill as="div" />
        <Button
          type="button"
          disabled={controller.isCreating}
          mod={['outlined']}
          onClick={handleCancel}
        >
          {translate('ui_processing_cancel')}
        </Button>
        <Button
          type="button"
          disabled={controller.isCreating}
          mod={['unelevated']}
          onClick={controller.save}
        >
          {translate(controller.isNew ? 'ui_processing_create' : 'ui_processing_save')}
        </Button>
      </TabList>
      {controller.isLoading
        ? <Loader />
        : (
          <SubmittingForm onSubmit={controller.save} ref={focusedRef as React.RefObject<HTMLFormElement>}>
            <layout-grid as="div">
              <layout-grid-inner as="div">
                <layout-grid-cell as='div' {...use({ 'span-tablet': 12, 'span-desktop': 5 })}>
                  <group as="div">
                    <InputGroup>{translate('authentication_user_credentials')}</InputGroup>
                  </group>
                  <group as="div">
                    <InputField
                      type='text'
                      name='login'
                      value={controller.credentials.login}
                      onChange={handleLoginChange}
                      disabled={!controller.isNew || controller.isCreating}
                      mod='surface'
                    >
                      {translate('authentication_user_name')}
                    </InputField>
                  </group>
                  <group as="div">
                    <InputField
                      type='password'
                      name='password'
                      value={controller.credentials.password}
                      onChange={handlePasswordChange}
                      disabled={controller.isCreating}
                      mod='surface'
                    >
                      {translate('authentication_user_password')}
                    </InputField>
                  </group>
                  <group as="div">
                    <InputField
                      type='password'
                      name='password_repeat'
                      value={controller.credentials.passwordRepeat}
                      onChange={handlePasswordRepeatChange}
                      disabled={controller.isCreating}
                      mod='surface'
                    >
                      {translate('authentication_user_password_repeat')}
                    </InputField>
                  </group>
                </layout-grid-cell>
                <layout-grid-cell as='div' {...use({ 'span-tablet': 12, 'span-desktop': 5 })}>
                  <group as="div">
                    <InputGroup>{translate('authentication_user_role')}</InputGroup>
                  </group>
                  {controller.roles.map((role, i) => (
                    <group as="div" key={role.roleId}>
                      <Checkbox
                        value={role.roleId}
                        name='role'
                        checkboxLabel={role.roleName || role.roleId}
                        onChange={checked => handleRoleChange(role.roleId, checked)}
                        checked={controller.credentials.roles.get(role.roleId)}
                        disabled={controller.isCreating}
                        mod='surface'
                      />
                    </group>
                  ))}
                </layout-grid-cell>
              </layout-grid-inner>
            </layout-grid>
          </SubmittingForm>
        )}
      {controller.error.responseMessage && (
        <ErrorMessage
          text={controller.error.responseMessage}
          hasDetails={controller.error.hasDetails}
          onShowDetails={controller.showDetails}
        />
      )}
    </TabsState>
  );
});