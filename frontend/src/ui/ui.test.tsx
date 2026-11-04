import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';

import { Alert, Button, Dialog, OptionCard, TextField } from '.';

describe('Button', () => {
  test('is a plain button of the chosen variant', () => {
    render(
      <>
        <Button variant="primary">Confirm</Button>
        <Button variant="secondary">Back</Button>
        <Button variant="danger">Cancel appointment</Button>
      </>,
    );

    for (const name of ['Confirm', 'Back', 'Cancel appointment']) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('type', 'button');
    }
    expect(screen.getByRole('button', { name: 'Confirm' })).toHaveAttribute('data-variant', 'primary');
  });

  test('is disabled and announced as busy while busy', async () => {
    const onClick = vi.fn();
    render(
      <Button variant="primary" busy onClick={onClick}>
        Confirm
      </Button>,
    );

    const button = screen.getByRole('button', { name: /Confirm/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('TextField', () => {
  test('has a visible label and announces its error', () => {
    render(<TextField label="E-mail" value="" onChange={() => {}} error="E-mail is required" />);

    const input = screen.getByLabelText('E-mail');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('E-mail is required');
    expect(input).toHaveAccessibleDescription('E-mail is required');
  });
});

describe('OptionCard', () => {
  function Options() {
    const [selected, setSelected] = useState<string>();
    return (
      <div>
        {['Golf', 'Corolla'].map((name) => (
          <OptionCard key={name} title={name} selected={selected === name} onSelect={() => setSelected(name)} />
        ))}
      </div>
    );
  }

  test('is reachable with Tab and selected with Enter or Space', async () => {
    const user = userEvent.setup();
    render(<Options />);

    await user.tab();
    expect(screen.getByRole('button', { name: 'Golf' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Golf' })).toHaveAttribute('aria-pressed', 'true');

    await user.tab();
    await user.keyboard(' ');
    expect(screen.getByRole('button', { name: 'Corolla' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Golf' })).toHaveAttribute('aria-pressed', 'false');
  });
});

describe('Dialog', () => {
  test('moves focus inside, keeps it there, and closes on Escape', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <>
        <button type="button">Outside</button>
        <Dialog title="Cancel this appointment?" onClose={onClose}>
          <Button variant="secondary" onClick={onClose}>
            Keep it
          </Button>
          <Button variant="danger">Cancel appointment</Button>
        </Dialog>
      </>,
    );

    expect(screen.getByRole('dialog', { name: 'Cancel this appointment?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Cancel appointment' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Keep it' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledOnce();
  });
});

describe('Alert', () => {
  test('errors use role alert, information uses role status', () => {
    render(
      <>
        <Alert tone="error">That time was just taken.</Alert>
        <Alert tone="info">Your session has expired.</Alert>
      </>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('That time was just taken.');
    expect(screen.getByRole('status')).toHaveTextContent('Your session has expired.');
  });
});
